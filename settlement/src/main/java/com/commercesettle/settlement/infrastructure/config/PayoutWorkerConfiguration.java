package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.infrastructure.client.HttpPayoutGateway;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PayoutWorkerConfiguration {
  /** 잘못된 조합(HTTP 제한 시간 ≥ 임대 시간 등)이면 기동을 멈춘다. */
  @Bean
  PayoutWorkerSettings payoutWorkerSettings(
      @Value("${settlement.payout-worker.lease-duration}") Duration leaseDuration,
      @Value("${settlement.payout-worker.http-timeout}") Duration httpTimeout,
      @Value("${settlement.payout-worker.unconfirmed-retry-delay}") Duration retryDelay,
      @Value("${settlement.payout-worker.max-attempts}") int maxAttempts) {
    return new PayoutWorkerSettings(leaseDuration, httpTimeout, retryDelay, maxAttempts);
  }

  @Bean
  PayoutGateway payoutGateway(
      RestClient.Builder builder,
      PayoutWorkerSettings settings,
      @Value("${settlement.payout.mock-base-url}") String baseUrl,
      @Value("${settlement.payout.lookup-scenario-version}") String lookupScenarioVersion) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(settings.httpTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(settings.httpTimeout());
    return new HttpPayoutGateway(
        builder.baseUrl(baseUrl).requestFactory(requestFactory).build(), lookupScenarioVersion);
  }
}
