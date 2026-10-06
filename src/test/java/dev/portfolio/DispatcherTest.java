package dev.portfolio;
import dev.portfolio.application.Dispatcher;
import dev.portfolio.infrastructure.ProviderClient;
import dev.portfolio.api.Submission;
import dev.portfolio.domain.Transaction.Scenario;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
class DispatcherTest {
 @Test void classifiesRemoteFailuresWithoutLeakingMessages(){
  assertThat(Dispatcher.classify(new ProviderClient.ProviderLimited())).isEqualTo("PROVIDER_RATE_LIMITED");
  assertThat(Dispatcher.classify(new ProviderClient.ProviderFailure(500))).isEqualTo("PROVIDER_HTTP_500");
  assertThat(Dispatcher.classify(new RuntimeException(new TimeoutException()))).isEqualTo("TIMEOUT");
 }
 @Test void rejectsInvalidFinancialInput(){
  try(var factory=Validation.buildDefaultValidatorFactory()){
   assertThat(factory.getValidator().validate(new Submission(new BigDecimal("-1.001"),"XXX",Scenario.SUCCESS))).hasSize(3);
  }
 }
}
