package dev.portfolio.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.portfolio.api.Submission;
import dev.portfolio.domain.Transaction.Scenario;
import dev.portfolio.infrastructure.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

class AdmissionTest {
  Dispatcher dispatcher(int rate, Sinks.Empty<Void> completion) {
    var provider = mock(ProviderClient.class);
    when(provider.send(any())).thenReturn(completion.asMono());
    var settings =
        new DispatchSettings(
            1,
            1,
            rate,
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofMillis(250));
    var d = new Dispatcher(provider, new SimpleMeterRegistry(), settings);
    d.start();
    return d;
  }

  Submission input() {
    return new Submission(BigDecimal.ONE, "PEN", Scenario.SUCCESS);
  }

  @Test
  void capacityRejectsAndRecoversWithoutUnboundedBuffer() throws Exception {
    var completion = Sinks.<Void>empty();
    var d = dispatcher(100, completion);
    try {
      d.submit(input(), "a", "a");
      d.submit(input(), "b", "b");
      assertThatThrownBy(() -> d.submit(input(), "c", "c")).hasMessage("CAPACITY_EXHAUSTED");
      completion.tryEmitEmpty();
      assertThatCode(() -> d.submit(input(), "d", "d")).doesNotThrowAnyException();
    } finally {
      completion.tryEmitEmpty();
      d.stop();
    }
  }

  @Test
  void rateRejectionDoesNotConsumeCapacity() throws Exception {
    var completion = Sinks.<Void>empty();
    var d = dispatcher(1, completion);
    try {
      d.submit(input(), "a", "a");
      assertThatThrownBy(() -> d.submit(input(), "b", "b")).hasMessage("RATE_LIMITED");
    } finally {
      completion.tryEmitEmpty();
      d.stop();
    }
  }
}
