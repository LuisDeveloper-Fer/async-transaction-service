package dev.portfolio.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Transaction(
    UUID id,
    BigDecimal amount,
    String currency,
    Scenario scenario,
    Status status,
    String correlationId,
    String traceId,
    Instant acceptedAt,
    Instant completedAt,
    String error) {
  public enum Scenario {
    SUCCESS,
    SLOW,
    ERROR,
    NEVER,
    RATE_LIMIT
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public Transaction withStatus(Status next, String failure) {
    return new Transaction(
        id,
        amount,
        currency,
        scenario,
        next,
        correlationId,
        traceId,
        acceptedAt,
        next == Status.RUNNING ? null : Instant.now(),
        failure);
  }

  public boolean terminal() {
    return completedAt != null;
  }
}
