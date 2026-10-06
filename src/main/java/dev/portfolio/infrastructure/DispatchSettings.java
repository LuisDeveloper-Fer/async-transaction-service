package dev.portfolio.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dispatch")
public record DispatchSettings(
    @DefaultValue("4") int concurrency,
    @DefaultValue("16") int queueCapacity,
    @DefaultValue("10") int ratePerSecond,
    @DefaultValue("500ms") Duration connectTimeout,
    @DefaultValue("1500ms") Duration responseTimeout,
    @DefaultValue("2s") Duration deadline,
    @DefaultValue("250ms") Duration acquireTimeout) {
  public DispatchSettings {
    if (concurrency < 1
        || concurrency > 64
        || queueCapacity < 1
        || queueCapacity > 500
        || ratePerSecond < 1
        || ratePerSecond > 10000)
      throw new IllegalArgumentException("Invalid dispatch capacity/rate configuration");
    for (var timeout : new Duration[] {connectTimeout, responseTimeout, deadline, acquireTimeout})
      if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
        throw new IllegalArgumentException("Timeouts must be positive and <= 30 seconds");
  }
}
