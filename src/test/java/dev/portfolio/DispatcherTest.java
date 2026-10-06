package dev.portfolio;

import static org.assertj.core.api.Assertions.*;

import dev.portfolio.api.Submission;
import dev.portfolio.application.Dispatcher;
import dev.portfolio.domain.Transaction.Scenario;
import dev.portfolio.infrastructure.ProviderClient;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class DispatcherTest {
  @Test
  void classifiesRemoteFailuresWithoutLeakingMessages() {
    assertThat(Dispatcher.classify(new ProviderClient.ProviderLimited()))
        .isEqualTo("PROVIDER_RATE_LIMITED");
    assertThat(Dispatcher.classify(new ProviderClient.ProviderFailure(500)))
        .isEqualTo("PROVIDER_HTTP_500");
    assertThat(Dispatcher.classify(new RuntimeException(new TimeoutException())))
        .isEqualTo("TIMEOUT");
  }

  @Test
  void rejectsInvalidFinancialInput() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(
              factory
                  .getValidator()
                  .validate(new Submission(new BigDecimal("-1.001"), "XXX", Scenario.SUCCESS)))
          .hasSize(3);
    }
  }
}
