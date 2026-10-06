package dev.portfolio.infrastructure;

import dev.portfolio.domain.Transaction;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Component
public class ProviderClient {
  private final ConnectionProvider pool;
  private final WebClient client;
  private final CircuitBreaker breaker;
  private final DispatchSettings settings;

  public ProviderClient(
      @Value("${provider.url:http://localhost:9091}") String url,
      MeterRegistry meters,
      DispatchSettings settings) {
    this.settings = settings;
    pool =
        ConnectionProvider.builder("provider")
            .maxConnections(settings.concurrency())
            .pendingAcquireMaxCount(settings.concurrency())
            .pendingAcquireTimeout(settings.acquireTimeout())
            .maxIdleTime(Duration.ofSeconds(20))
            .maxLifeTime(Duration.ofMinutes(2))
            .metrics(true)
            .build();
    var http =
        HttpClient.create(pool)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) settings.connectTimeout().toMillis())
            .responseTimeout(settings.responseTimeout());
    client =
        WebClient.builder()
            .baseUrl(url)
            .clientConnector(new ReactorClientHttpConnector(http))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16384))
            .build();
    var config =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .permittedNumberOfCallsInHalfOpenState(2)
            .ignoreExceptions(ProviderLimited.class)
            .build();
    var registry = CircuitBreakerRegistry.of(config);
    breaker = registry.circuitBreaker("provider");
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
  }

  public Mono<Void> send(Transaction tx) {
    return Mono.defer(
            () ->
                client
                    .post()
                    .uri("/transactions")
                    .header("X-Correlation-ID", tx.correlationId())
                    .header(
                        "traceparent",
                        "00-"
                            + tx.traceId()
                            + "-"
                            + tx.id().toString().replace("-", "").substring(0, 16)
                            + "-01")
                    .bodyValue(
                        Map.of(
                            "id",
                            tx.id(),
                            "amount",
                            tx.amount(),
                            "currency",
                            tx.currency(),
                            "scenario",
                            tx.scenario()))
                    .exchangeToMono(
                        response -> {
                          int status = response.statusCode().value();
                          if (status >= 200 && status < 300) return response.releaseBody();
                          return response
                              .releaseBody()
                              .then(
                                  Mono.error(
                                      status == 429
                                          ? new ProviderLimited()
                                          : new ProviderFailure(status)));
                        }))
        .timeout(settings.deadline())
        .transformDeferred(CircuitBreakerOperator.of(breaker));
  }

  public String circuitState() {
    return breaker.getState().name();
  }

  @PreDestroy
  public void close() {
    pool.dispose();
  }

  public static final class ProviderLimited extends RuntimeException {}

  public static final class ProviderFailure extends RuntimeException {
    public final int status;

    public ProviderFailure(int status) {
      this.status = status;
    }
  }
}
