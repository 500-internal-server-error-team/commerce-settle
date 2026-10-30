package com.commercesettle.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.commercesettle.settlement.infrastructure.batch.DailySettlementLauncher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.dev-fixtures.enabled=true",
      "settlement.daily-batch.schedule-enabled=false"
    })
@Testcontainers
class DevFixtureIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired DevFixtureLoader loader;
  @Autowired DailySettlementLauncher launcher;
  @Autowired JdbcTemplate jdbc;
  @Autowired SettlementItemRepository items;
  @Autowired com.commercesettle.settlement.domain.repository.SettlementBatchRepository batches;

  @Test
  void runsActualBatchWithCutoffAndResumesWithoutDuplicates() throws Exception {
    assertThat(count("settlement_items")).isEqualTo(8);
    assertThat(count("payouts")).isZero();
    // 일반 배치가 이미 있으면 미처리 fixture를 완료한 것으로 보고하지 않는다.
    var existing =
        batches.save(
            com.commercesettle.settlement.domain.model.SettlementBatch.reserve(
                UUID.fromString("00000000-0000-4000-8000-000000000002"),
                LocalDate.of(2026, 9, 1),
                "delivery-daily-v1",
                1_000,
                Instant.parse("2026-09-01T01:00:00Z")));
    assertThat(launcher.runFixture(LocalDate.of(2026, 9, 1), loader.itemIds()))
        .isEqualTo(BatchStatus.FAILED);
    assertThat(count("payouts")).isZero();
    jdbc.update("DELETE FROM settlement.settlement_batches WHERE id=?", existing.getId());
    // 판매자 A 커밋 후 B에서 일반 항목 선점 위험을 발견하면 B만 롤백한다.
    Instant at = Instant.parse("2026-09-01T00:00:00Z");
    SettlementItem foreign =
        SettlementItem.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.fromString("00000000-0000-4000-8000-000000000023"),
            UUID.fromString("00000000-0000-4000-8000-000000000022"),
            new SettlementAmounts(39_000, 0, 39_000, 39_000, 0, 5_850, 33_150),
            0,
            at);
    foreign.markPayable(at, at, at);
    items.save(foreign);
    assertThat(launcher.runFixture(LocalDate.of(2026, 9, 1), loader.itemIds()))
        .isEqualTo(BatchStatus.FAILED);
    assertThat(count("payouts")).isEqualTo(1);
    assertThat(items.findById(foreign.getId()).orElseThrow().getStatus().name())
        .isEqualTo("PAYABLE");
    jdbc.update("DELETE FROM settlement.settlement_items WHERE id=?", foreign.getId());
    assertThat(launcher.runFixture(LocalDate.of(2026, 9, 1), loader.itemIds()))
        .isEqualTo(BatchStatus.COMPLETED);
    assertThat(count("settlement_batches")).isEqualTo(2);
    assertThat(count("payouts")).isEqualTo(2);
    assertThat(count("payout_jobs")).isEqualTo(2);
    assertThat(count("settlement_reservations")).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT sum(amount) FROM settlement.payouts", Long.class))
        .isEqualTo(72_250);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.settlement_items WHERE status='PAYABLE'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.settlement_items WHERE status='PAYOUT_RESERVED' AND ready_at >= '2026-09-01T01:00:00Z'",
                Integer.class))
        .isZero();
    var before = jdbc.queryForList("SELECT * FROM settlement.settlement_items ORDER BY id");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> loader.load());
      var second = executor.submit(() -> loader.load());
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertThat(launcher.runFixture(LocalDate.of(2026, 9, 1), loader.itemIds()))
        .isEqualTo(BatchStatus.COMPLETED);
    assertThat(jdbc.queryForList("SELECT * FROM settlement.settlement_items ORDER BY id"))
        .isEqualTo(before);
    assertThat(count("payouts")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.batch_job_execution WHERE status='COMPLETED'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void acceptsExistingJobOwnerAndRequiresSchedulerDisabled() {
    var launcher = org.mockito.Mockito.mock(DailySettlementLauncher.class);
    org.mockito.Mockito.when(
            launcher.runFixture(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet()))
        .thenReturn(BatchStatus.STARTED);
    var context =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(
                com.commercesettle.settlement.infrastructure.config.DevFixtureConfiguration.class)
            .withBean(
                DevFixtureLoader.class, () -> org.mockito.Mockito.mock(DevFixtureLoader.class))
            .withBean(DailySettlementLauncher.class, () -> launcher);
    context.run(c -> assertThat(c).doesNotHaveBean("settlementDevFixtures"));
    context.withPropertyValues("app.dev-fixtures.enabled=true").run(c -> assertThat(c).hasFailed());
    context
        .withPropertyValues(
            "app.dev-fixtures.enabled=true",
            "app.dev-fixtures.run-scenarios=true",
            "settlement.daily-batch.schedule-enabled=false")
        .run(
            c ->
                org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                            c.getBean(org.springframework.boot.ApplicationRunner.class)
                                .run(new org.springframework.boot.DefaultApplicationArguments()))
                    .doesNotThrowAnyException());
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Integer.class);
  }
}
