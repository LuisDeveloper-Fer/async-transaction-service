package dev.portfolio.application;

import dev.portfolio.api.Submission;
import dev.portfolio.domain.Transaction;
import dev.portfolio.domain.Transaction.Status;
import dev.portfolio.infrastructure.DispatchSettings;
import dev.portfolio.infrastructure.ProviderClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.*;
import io.micrometer.core.instrument.*;
import jakarta.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.*;

@Service
public class Dispatcher {
  private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
  private final Map<UUID, Transaction> records = new ConcurrentHashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final ArrayBlockingQueue<Transaction> buffer;
  private final Sinks.Many<Transaction> queue;
  private final RateLimiter limiter;
  private final DispatchSettings settings;
  private final ProviderClient provider;
  private final MeterRegistry meters;
  private final CountDownLatch drained = new CountDownLatch(1);
  private volatile boolean accepting = true;
  private Disposable worker;

  public Dispatcher(ProviderClient provider, MeterRegistry meters, DispatchSettings settings) {
    this.provider = provider;
    this.meters = meters;
    this.settings = settings;
    buffer = new ArrayBlockingQueue<>(settings.queueCapacity());
    queue = Sinks.many().unicast().onBackpressureBuffer(buffer);
    limiter =
        RateLimiter.of(
            "admission",
            RateLimiterConfig.custom()
                .limitForPeriod(settings.ratePerSecond())
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
    meters.gauge("transactions.inflight", inFlight);
    meters.gauge("transactions.queue.size", buffer, Queue::size);
  }

  @PostConstruct
  void start() {
    worker =
        queue
            .asFlux()
            .flatMap(this::deliver, settings.concurrency(), 1)
            .doFinally(signal -> drained.countDown())
            .subscribe(
                ignored -> {},
                e -> {
                  accepting = false;
                  log.error("dispatcher stopped", e);
                });
  }

  public synchronized Transaction submit(Submission request, String correlation, String traceId) {
    if (!accepting) throw rejected("SHUTTING_DOWN", 503);
    if (!limiter.acquirePermission()) throw rejected("RATE_LIMITED", 429);
    cleanup();
    if (inFlight.get() >= settings.concurrency() + settings.queueCapacity()
        || records.size() >= 1000) throw rejected("CAPACITY_EXHAUSTED", 503);
    var tx =
        new Transaction(
            UUID.randomUUID(),
            request.amount(),
            request.currency(),
            request.scenario(),
            Status.QUEUED,
            correlation,
            traceId,
            Instant.now(),
            null,
            null);
    records.put(tx.id(), tx);
    inFlight.incrementAndGet();
    var result = queue.tryEmitNext(tx);
    if (result.isFailure()) {
      records.remove(tx.id());
      inFlight.decrementAndGet();
      throw rejected("CAPACITY_EXHAUSTED", 503);
    }
    meters.counter("transactions.accepted").increment();
    return tx;
  }

  private Rejected rejected(String code, int status) {
    meters.counter("transactions.rejected", "reason", code).increment();
    return new Rejected(code, status);
  }

  private void cleanup() {
    var cutoff = Instant.now().minus(Duration.ofMinutes(15));
    records.values().removeIf(t -> t.terminal() && t.completedAt().isBefore(cutoff));
    if (records.size() >= 1000)
      records.values().stream()
          .filter(Transaction::terminal)
          .min(Comparator.comparing(Transaction::completedAt))
          .ifPresent(t -> records.remove(t.id()));
  }

  private Mono<Void> deliver(Transaction tx) {
    return Mono.defer(
        () -> {
          records.put(tx.id(), tx.withStatus(Status.RUNNING, null));
          meters
              .timer("transactions.queue.wait")
              .record(Duration.between(tx.acceptedAt(), Instant.now()));
          var sample = io.micrometer.core.instrument.Timer.start(meters);
          return provider
              .send(tx)
              .then(Mono.<Void>fromRunnable(() -> finish(tx, null)))
              .onErrorResume(
                  e -> {
                    finish(tx, classify(e));
                    return Mono.empty();
                  })
              .doFinally(
                  signal -> {
                    sample.stop(meters.timer("transactions.delivery"));
                    inFlight.decrementAndGet();
                  });
        });
  }

  private void finish(Transaction tx, String error) {
    records.put(tx.id(), tx.withStatus(error == null ? Status.SUCCEEDED : Status.FAILED, error));
    meters
        .counter("transactions.completed", "outcome", error == null ? "SUCCESS" : error)
        .increment();
    meters
        .timer("transactions.end.to.end")
        .record(Duration.between(tx.acceptedAt(), Instant.now()));
    log.info(
        "transaction={} correlation={} trace={} outcome={}",
        tx.id(),
        tx.correlationId(),
        tx.traceId(),
        error == null ? "SUCCESS" : error);
  }

  public static String classify(Throwable e) {
    if (e instanceof CallNotPermittedException) return "CIRCUIT_OPEN";
    if (e instanceof ProviderClient.ProviderLimited) return "PROVIDER_RATE_LIMITED";
    if (e instanceof ProviderClient.ProviderFailure f) return "PROVIDER_HTTP_" + f.status;
    for (Throwable c = e; c != null; c = c.getCause())
      if (c instanceof TimeoutException || c.getClass().getSimpleName().contains("Timeout"))
        return "TIMEOUT";
    return "CONNECTION_ERROR";
  }

  public Optional<Transaction> get(UUID id) {
    return Optional.ofNullable(records.get(id));
  }

  public List<Transaction> recent() {
    return records.values().stream()
        .sorted(Comparator.comparing(Transaction::acceptedAt).reversed())
        .limit(50)
        .toList();
  }

  public Map<String, Object> stats() {
    return Map.of(
        "inFlight",
        inFlight.get(),
        "queued",
        buffer.size(),
        "capacity",
        settings.concurrency() + settings.queueCapacity(),
        "circuit",
        provider.circuitState(),
        "accepting",
        accepting);
  }

  @PreDestroy
  public void stop() throws InterruptedException {
    synchronized (this) {
      accepting = false;
      queue.tryEmitComplete();
    }
    if (!drained.await(5, TimeUnit.SECONDS)) {
      worker.dispose();
      records.replaceAll((id, t) -> t.terminal() ? t : t.withStatus(Status.CANCELLED, "SHUTDOWN"));
    }
  }

  public static final class Rejected extends RuntimeException {
    public final int status;

    public Rejected(String code, int status) {
      super(code);
      this.status = status;
    }
  }
}
