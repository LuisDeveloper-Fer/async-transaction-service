package dev.portfolio;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.portfolio.application.Dispatcher;
import dev.portfolio.domain.Transaction;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "dispatch.rate-per-second=1000")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AsyncIntegrationTest {
  static final AtomicReference<String> mode = new AtomicReference<>("SUCCESS");
  static final AtomicReference<String> correlation = new AtomicReference<>();
  static final AtomicReference<String> trace = new AtomicReference<>();
  static final DisposableServer provider =
      HttpServer.create()
          .port(0)
          .handle(
              (req, res) -> {
                correlation.set(req.requestHeaders().get("X-Correlation-ID"));
                trace.set(req.requestHeaders().get("traceparent"));
                return req.receive()
                    .then()
                    .then(
                        Mono.defer(
                            () ->
                                switch (mode.get()) {
                                  case "ERROR" -> res.status(500).send().then();
                                  case "RATE_LIMIT" -> res.status(429).send().then();
                                  case "NEVER" -> Mono.<Void>never();
                                  case "SLOW" ->
                                      Mono.delay(Duration.ofSeconds(4)).then(res.send().then());
                                  default -> res.status(200).send().then();
                                }));
              })
          .bindNow();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry props) {
    props.add("provider.url", () -> "http://localhost:" + provider.port());
  }

  @LocalServerPort int port;
  @Autowired Dispatcher dispatcher;

  WebClient client() {
    return WebClient.create("http://localhost:" + port);
  }

  Transaction submit() {
    return client()
        .post()
        .uri("/api/transactions")
        .header("X-Correlation-ID", "integration-test")
        .header("traceparent", "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01")
        .bodyValue(Map.of("amount", 10, "currency", "PEN", "scenario", "SUCCESS"))
        .retrieve()
        .bodyToMono(Transaction.class)
        .block();
  }

  Transaction terminal(UUID id) {
    await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.get(id).orElseThrow().terminal());
    return dispatcher.get(id).orElseThrow();
  }

  @AfterAll
  static void close() {
    provider.disposeNow();
  }

  @Test
  @Order(1)
  void successPropagatesIdentifiers() {
    mode.set("SUCCESS");
    var tx = submit();
    assertThat(terminal(tx.id()).status()).isEqualTo(Transaction.Status.SUCCEEDED);
    assertThat(correlation.get()).isEqualTo("integration-test");
    assertThat(trace.get()).startsWith("00-1234567890abcdef1234567890abcdef-");
  }

  @Test
  @Order(2)
  void remoteRateLimitIsObservable() {
    mode.set("RATE_LIMIT");
    assertThat(terminal(submit().id()).error()).isEqualTo("PROVIDER_RATE_LIMITED");
  }

  @Test
  @Order(3)
  void returnsBeforeSlowProviderAndTimesOut() {
    mode.set("SLOW");
    long start = System.nanoTime();
    var tx = submit();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    assertThat(terminal(tx.id()).error()).isEqualTo("TIMEOUT");
  }

  @Test
  @Order(4)
  void neverRespondingProviderIsBounded() {
    mode.set("NEVER");
    assertThat(terminal(submit().id()).error()).isEqualTo("TIMEOUT");
  }

  @Test
  @Order(5)
  void breakerOpensAndRecovers() {
    mode.set("ERROR");
    for (int i = 0; i < 6 && !dispatcher.stats().get("circuit").equals("OPEN"); i++)
      terminal(submit().id());
    assertThat(dispatcher.stats().get("circuit")).isEqualTo("OPEN");
    assertThat(terminal(submit().id()).error()).isEqualTo("CIRCUIT_OPEN");
    mode.set("SUCCESS");
    await()
        .pollDelay(Duration.ofSeconds(5))
        .atMost(Duration.ofSeconds(8))
        .until(() -> terminal(submit().id()).status() == Transaction.Status.SUCCEEDED);
    assertThat(terminal(submit().id()).status()).isEqualTo(Transaction.Status.SUCCEEDED);
    assertThat(dispatcher.stats().get("circuit")).isEqualTo("CLOSED");
  }

  @Test
  @Order(6)
  void invalidRequestAndMissingTransactionHaveCorrectHttpStatus() {
    assertThat(
            client()
                .post()
                .uri("/api/transactions")
                .bodyValue(Map.of("amount", -1, "currency", "XXX", "scenario", "SUCCESS"))
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block())
        .isEqualTo(400);
    assertThat(
            client()
                .get()
                .uri("/api/transactions/" + UUID.randomUUID())
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block())
        .isEqualTo(404);
    assertThat(
            client().get().uri("/actuator/prometheus").retrieve().bodyToMono(String.class).block())
        .contains("transactions_completed_total", "transactions_delivery_seconds");
  }
}
