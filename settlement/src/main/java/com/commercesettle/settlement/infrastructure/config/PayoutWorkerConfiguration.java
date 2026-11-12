package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.infrastructure.client.HttpPayoutGateway;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
public class PayoutWorkerConfiguration {
  /** 잘못된 조합(HTTP 제한 시간 ≥ 임대 시간, 복구 전용 슬롯 ≥ 전체 슬롯 등)이면 기동을 멈춘다. */
  @Bean
  PayoutWorkerSettings payoutWorkerSettings(
      @Value("${settlement.payout-worker.lease-duration}") Duration leaseDuration,
      @Value("${settlement.payout-worker.http-timeout}") Duration httpTimeout,
      @Value("${settlement.payout-worker.unconfirmed-retry-delay}") Duration retryDelay,
      @Value("${settlement.payout-worker.max-attempts}") int maxAttempts,
      @Value("${settlement.payout-worker.max-concurrency}") int maxConcurrency,
      @Value("${settlement.payout-worker.recovery-slots}") int recoverySlots) {
    return new PayoutWorkerSettings(
        leaseDuration, httpTimeout, retryDelay, maxAttempts, maxConcurrency, recoverySlots);
  }

  /**
   * 지급 실행 풀. 디스패처가 빈 슬롯만큼만 넘기므로 스레드는 슬롯 수와 같다. 대기열은 실행을 마친 스레드가 풀로 돌아오기 전 잠깐만 쓰인다. 종료 때는 실행 중인 지급의
   * 결과 반영을 기다린다.
   */
  @Bean(defaultCandidate = false)
  @ConditionalOnProperty(
      prefix = "settlement.payout-worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  ThreadPoolTaskExecutor payoutWorkerExecutor(PayoutWorkerSettings settings) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(settings.maxConcurrency());
    executor.setMaxPoolSize(settings.maxConcurrency());
    executor.setQueueCapacity(settings.maxConcurrency());
    executor.setThreadNamePrefix("payout-worker-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }

  @Bean
  PayoutGateway payoutGateway(
      RestClient.Builder builder,
      PayoutWorkerSettings settings,
      @Value("${settlement.payout.mock-base-url}") String baseUrl,
      @Value("${settlement.payout.mock-credential}") String credential,
      @Value("${settlement.payout.lookup-scenario-version}") String lookupScenarioVersion) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(settings.httpTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(settings.httpTimeout());
    return new HttpPayoutGateway(
        builder.baseUrl(baseUrl).requestFactory(requestFactory).build(),
        credential,
        lookupScenarioVersion);
  }
}
