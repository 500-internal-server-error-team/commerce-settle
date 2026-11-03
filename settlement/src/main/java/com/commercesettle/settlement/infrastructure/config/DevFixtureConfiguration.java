package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.infrastructure.batch.DailySettlementLauncher;
import com.commercesettle.settlement.infrastructure.persistence.DevFixtureLoader;
import java.time.LocalDate;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureConfiguration {
  @Bean
  ApplicationRunner settlementDevFixtures(
      DevFixtureLoader loader,
      DailySettlementLauncher launcher,
      @Value("${app.dev-fixtures.run-scenarios:false}") boolean runScenarios,
      @Value("${settlement.daily-batch.schedule-enabled:true}") boolean scheduled,
      @Value("${settlement.payout-worker.enabled:true}") boolean workerEnabled) {
    if (scheduled || workerEnabled)
      throw new IllegalArgumentException(
          "Disable SETTLEMENT_DAILY_BATCH_ENABLED and SETTLEMENT_PAYOUT_WORKER_ENABLED for dev fixtures");
    return args -> {
      loader.load();
      if (runScenarios) {
        LocalDate batchDate = LocalDate.of(2026, 9, 1);
        BatchStatus result = launcher.runFixture(batchDate, loader.itemIds());
        if (result != BatchStatus.COMPLETED && result != BatchStatus.STARTED) {
          throw new IllegalStateException("Fixture daily settlement did not complete");
        }
        // 커머스 지급 컨텍스트 조회 실패는 FAILED지만, 지급 전 검증 거절로 판매자를 건너뛰면 COMPLETED다.
        if (result == BatchStatus.COMPLETED
            && loader.hasUnsettledItemsBefore(SettlementBatch.cutoffOf(batchDate))) {
          throw new IllegalStateException(
              "Fixture daily settlement skipped sellers; check Commerce settlement context");
        }
      }
    };
  }
}
