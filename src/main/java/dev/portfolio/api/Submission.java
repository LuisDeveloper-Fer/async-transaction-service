package dev.portfolio.api;
import dev.portfolio.domain.Transaction.Scenario;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record Submission(@NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer=9,fraction=2) BigDecimal amount,
 @NotBlank @Pattern(regexp="PEN|USD|EUR") String currency, @NotNull Scenario scenario) {}
