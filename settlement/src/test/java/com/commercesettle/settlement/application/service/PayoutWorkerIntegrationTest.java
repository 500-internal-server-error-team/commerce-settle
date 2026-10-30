package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 지급 작업 임대와 결과 반영을 확인한다. 대역은 결과를 정해 주는 대역 구현으로 바꾼다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.payout-worker.unconfirmed-retry-delay=0s",
      "settlement.payout-worker.max-attempts=3"
    })
@Testcontainers
class PayoutWorkerIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final LocalDate BATCH_DATE = LocalDate.of(2026, 10, 1);
  private static final Instant SUCCEEDED_AT = Instant.parse("2026-10-01T01:30:00.123456Z");
  private static final UUID EXTERNAL = UUID.randomUUID();

  @Autowired PayoutWorker worker;
  @Autowired SellerSettlementService sellers;
  @Autowired SettlementItemRepository items;
  @Autowired PayoutJobRepository jobs;
  @Autowired JdbcTemplate jdbc;
  @Autowired ScriptedGateway gateway;

  private UUID payoutId;
  private UUID batchId;
  private Connection itemLock;

  @BeforeEach
  void createPayout() {
    batchId = settle(UUID.randomUUID());
    payoutId =
        jdbc.queryForObject(
            "SELECT id FROM settlement.payouts WHERE batch_id = ?", UUID.class, batchId);
  }

  private UUID settle(UUID seller) {
    Instant readyAt = SettlementBatch.cutoffOf(BATCH_DATE).minusSeconds(60);
    for (long base : List.of(10_000L, 20_000L)) {
      long fee = base * 15 / 100;
      SettlementItem item =
          SettlementItem.create(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              seller,
              new SettlementAmounts(base, 0, base, base, 0, fee, base - fee),
              0,
              readyAt.minusSeconds(600));
      item.markPayable(readyAt, readyAt, readyAt);
      items.save(item);
    }
    return sellers.settle(seller, BATCH_DATE).orElseThrow().getId();
  }

  @AfterEach
  void deleteRows() throws SQLException {
    releaseItemLock();
    for (String table :
        List.of(
            "settlement_items",
            "settlement_batches",
            "payouts",
            "payout_jobs",
            "settlement_reservations")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
    gateway.reset();
  }

  @Test
  @DisplayName("성공이 확인되면 지급·항목·예약·배치·작업을 함께 완료하고 대역의 성공 시각을 남긴다")
  void completesEverythingOnSuccess() {
    gateway.executeResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    assertThat(worker.runOnce()).isTrue();

    assertThat(payout())
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("external_ref", EXTERNAL)
        .containsEntry("hold_reason", null);
    assertThat(
            jdbc.queryForObject(
                    "SELECT succeeded_at FROM settlement.payouts WHERE id = ?",
                    OffsetDateTime.class,
                    payoutId)
                .toInstant())
        .isEqualTo(SUCCEEDED_AT);
    assertThat(job())
        .containsEntry("status", "DONE")
        .containsEntry("attempts", 1)
        .containsEntry("lease_token", null);
    assertThat(itemStatuses()).containsOnly("PAID");
    assertThat(reservationStatuses()).containsOnly("CONSUMED");
    assertThat(batchStatus()).isEqualTo("COMPLETED");
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  @DisplayName("확정 거절이면 지급을 거절로, 배치·작업을 운영 확인으로 두고 항목·예약은 풀지 않는다")
  void keepsReservationOnDecline() {
    gateway.executeResults.add(new PayoutGateway.Result.Declined(EXTERNAL));

    worker.runOnce();

    assertThat(payout())
        .containsEntry("status", "DECLINED")
        .containsEntry("hold_reason", "CONFIRMED_REJECTION");
    assertThat(job()).containsEntry("status", "REVIEW_REQUIRED");
    assertThat(batchStatus()).isEqualTo("REVIEW_REQUIRED");
    assertThat(itemStatuses()).containsOnly("PAYOUT_RESERVED");
    assertThat(reservationStatuses()).containsOnly("ACTIVE");
    assertThat(worker.runOnce()).isFalse();
    assertThat(gateway.executed).hasSize(1);
  }

  @Test
  @DisplayName("결과를 모르면 예약을 유지하고, 다음 실행은 재실행하지 않고 원거래 조회로 결과를 받는다")
  void unconfirmedResultIsRecoveredByLookup() {
    gateway.executeResults.add(new PayoutGateway.Result.Unconfirmed("HTTP 504"));
    gateway.lookupResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    worker.runOnce();

    assertThat(payout()).containsEntry("status", "UNKNOWN").containsEntry("hold_reason", "UNKNOWN");
    assertThat(job()).containsEntry("status", "RETRY");
    assertThat(reservationStatuses()).containsOnly("ACTIVE");

    worker.runOnce();

    assertThat(payout()).containsEntry("status", "SUCCEEDED");
    assertThat(gateway.executed).hasSize(1);
    assertThat(gateway.lookedUp).hasSize(1);
  }

  @Test
  @DisplayName("조회에서 거래가 없다고 확인되면 같은 업무 키와 본문으로 다시 보낸다")
  void resendsSameRequestWhenTransactionIsMissing() {
    gateway.executeResults.add(new PayoutGateway.Result.Unconfirmed("connection reset"));
    gateway.lookupResults.add(new PayoutGateway.Result.NotFound());
    gateway.executeResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    worker.runOnce();
    worker.runOnce();

    assertThat(gateway.executed).hasSize(2);
    assertThat(gateway.executed.get(1)).isEqualTo(gateway.executed.get(0));
    assertThat(payout()).containsEntry("status", "SUCCEEDED");
  }

  @Test
  @DisplayName("임대를 잃은 뒤 도착한 결과는 버리고, 임대가 만료되면 다른 실행이 조회로 이어받는다")
  void staleResultIsDiscardedAndExpiredLeaseIsRecovered() {
    gateway.beforeResult =
        () -> jdbc.update("UPDATE settlement.payout_jobs SET lease_token = gen_random_uuid()");
    gateway.executeResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    worker.runOnce();

    assertThat(payout()).containsEntry("status", "PROCESSING");
    assertThat(job()).containsEntry("status", "RUNNING");
    assertThat(itemStatuses()).containsOnly("PAYOUT_RESERVED");

    gateway.beforeResult = () -> {};
    jdbc.update("UPDATE settlement.payout_jobs SET lease_until = now() - interval '1 second'");
    gateway.lookupResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    assertThat(worker.runOnce()).isTrue();

    assertThat(payout()).containsEntry("status", "SUCCEEDED");
    assertThat(job()).containsEntry("status", "DONE").containsEntry("attempts", 2);
    assertThat(gateway.executed).hasSize(1);
    assertThat(gateway.lookedUp).hasSize(1);
  }

  @Test
  @DisplayName("미확정이 시도 상한까지 이어지면 예약을 유지한 채 운영 확인으로 넘긴다")
  void exhaustedAttemptsRequireReview() {
    gateway.executeResults.add(new PayoutGateway.Result.Unconfirmed("HTTP 503"));
    gateway.lookupResults.add(new PayoutGateway.Result.Unconfirmed("HTTP 503"));
    gateway.lookupResults.add(new PayoutGateway.Result.Unconfirmed("HTTP 503"));

    worker.runOnce();
    worker.runOnce();
    worker.runOnce();

    assertThat(job()).containsEntry("status", "REVIEW_REQUIRED").containsEntry("attempts", 3);
    assertThat(payout()).containsEntry("status", "UNKNOWN");
    assertThat(batchStatus()).isEqualTo("REVIEW_REQUIRED");
    assertThat(reservationStatuses()).containsOnly("ACTIVE");
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  @DisplayName("호출이 예외로 끝나고 임대 회수가 반복되면 상한에서 호출 없이 운영 확인으로 넘긴다")
  void repeatedFailuresStopAtAttemptLimit() {
    gateway.beforeResult =
        () -> {
          throw new IllegalStateException("worker stopped during the call");
        };
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException.class);
      expireLease();
    }

    assertThat(worker.runOnce()).isTrue();

    assertThat(job()).containsEntry("status", "REVIEW_REQUIRED").containsEntry("attempts", 4);
    assertThat(payout()).containsEntry("status", "UNKNOWN").containsEntry("hold_reason", "UNKNOWN");
    assertThat(batchStatus()).isEqualTo("REVIEW_REQUIRED");
    assertThat(reservationStatuses()).containsOnly("ACTIVE");
    assertThat(gateway.executed.size() + gateway.lookedUp.size()).isEqualTo(3);
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  @DisplayName("호출 전에 중단된 임대가 상한까지 회수되면 지급은 대기 그대로 두고 운영 확인으로 넘긴다")
  void abandonedLeasesStopAtAttemptLimit() {
    for (int i = 0; i < 3; i++) {
      assertThat(jobs.leaseNext("stopped-worker", Duration.ofSeconds(30))).isPresent();
      expireLease();
    }

    assertThat(worker.runOnce()).isTrue();

    assertThat(job()).containsEntry("status", "REVIEW_REQUIRED").containsEntry("attempts", 4);
    assertThat(payout()).containsEntry("status", "PENDING");
    assertThat(batchStatus()).isEqualTo("REVIEW_REQUIRED");
    assertThat(gateway.executed).isEmpty();
    assertThat(gateway.lookedUp).isEmpty();
  }

  @Test
  @DisplayName("거래 부재를 확인했어도 임대에 호출 제한 시간만큼 남지 않았으면 재전송하지 않고 다음 조회를 기다린다")
  void doesNotResendWithoutEnoughLeaseTime() {
    gateway.executeResults.add(new PayoutGateway.Result.Unconfirmed("connection reset"));
    gateway.lookupResults.add(new PayoutGateway.Result.NotFound());
    worker.runOnce();
    gateway.beforeResult =
        () ->
            jdbc.update(
                "UPDATE settlement.payout_jobs SET lease_until = now() + interval '2 seconds'");

    worker.runOnce();

    assertThat(gateway.executed).hasSize(1);
    assertThat(job()).containsEntry("status", "RETRY");
    assertThat(payout()).containsEntry("status", "UNKNOWN");
  }

  @Test
  @DisplayName("성공했지만 완료할 항목 합계가 지급 금액과 다르면 성공만 남기고 항목·예약은 두고 운영 확인으로 넘긴다")
  void successWithChangedItemsRequiresReview() {
    gateway.beforeResult =
        () ->
            jdbc.update(
                "UPDATE settlement.settlement_items SET status = 'REFUND_RESERVED' WHERE id ="
                    + " (SELECT id FROM settlement.settlement_items WHERE payout_id = ? LIMIT 1)",
                payoutId);
    gateway.executeResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    worker.runOnce();

    assertThat(payout()).containsEntry("status", "SUCCEEDED");
    assertThat(job()).containsEntry("status", "REVIEW_REQUIRED");
    assertThat(batchStatus()).isEqualTo("REVIEW_REQUIRED");
    assertThat(itemStatuses()).containsExactlyInAnyOrder("PAYOUT_RESERVED", "REFUND_RESERVED");
    assertThat(reservationStatuses()).containsOnly("ACTIVE");
  }

  @Test
  @DisplayName("항목 잠금 대기가 상한을 넘으면 결과 반영을 롤백하고 임대를 둔 채 다음 작업을 처리하며, 만료 뒤 조회로 이어받는다")
  void lockTimeoutRollsBackResultAndNextJobRuns() throws SQLException {
    UUID otherPayoutId =
        jdbc.queryForObject(
            "SELECT id FROM settlement.payouts WHERE batch_id = ?",
            UUID.class,
            settle(UUID.randomUUID()));
    // 잠글 지급의 작업을 먼저 임대하게 한다.
    jdbc.update(
        "UPDATE settlement.payout_jobs SET next_run_at = next_run_at - interval '1 minute'"
            + " WHERE payout_id = ?",
        payoutId);
    gateway.beforeResult = this::lockItems;
    gateway.executeResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));
    gateway.executeResults.add(new PayoutGateway.Result.Succeeded(UUID.randomUUID(), SUCCEEDED_AT));

    assertThatThrownBy(worker::runOnce).isInstanceOf(DataAccessException.class);

    assertThat(payout()).containsEntry("status", "PROCESSING");
    assertThat(itemStatuses()).containsOnly("PAYOUT_RESERVED");
    assertThat(reservationStatuses()).containsOnly("ACTIVE");
    assertThat(batchStatus()).isEqualTo("PROCESSING");
    assertThat(job()).containsEntry("status", "RUNNING").doesNotContainEntry("lease_token", null);

    assertThat(worker.runOnce()).isTrue();

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.payout_jobs WHERE payout_id = ?",
                String.class,
                otherPayoutId))
        .isEqualTo("DONE");

    gateway.beforeResult = () -> {};
    releaseItemLock();
    jdbc.update(
        "UPDATE settlement.payout_jobs SET lease_until = now() - interval '1 second'"
            + " WHERE payout_id = ?",
        payoutId);
    gateway.lookupResults.add(new PayoutGateway.Result.Succeeded(EXTERNAL, SUCCEEDED_AT));

    assertThat(worker.runOnce()).isTrue();

    assertThat(payout()).containsEntry("status", "SUCCEEDED");
    assertThat(job()).containsEntry("status", "DONE").containsEntry("attempts", 2);
    assertThat(itemStatuses()).containsOnly("PAID");
  }

  /** 애플리케이션 풀 밖의 별도 연결로 이 지급의 항목을 잠근다. 이미 잠갔으면 그대로 둔다. */
  private void lockItems() {
    if (itemLock != null) {
      return;
    }
    try {
      itemLock =
          DriverManager.getConnection(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      itemLock.setAutoCommit(false);
      try (Statement timeout = itemLock.createStatement();
          PreparedStatement lock =
              itemLock.prepareStatement(
                  "SELECT id FROM settlement.settlement_items WHERE payout_id = ? FOR UPDATE")) {
        // 잠금 대기 상한이 빠지는 회귀에도 테스트가 멈추지 않고 잠금이 풀려 단언에서 실패하게 한다.
        timeout.execute("SET idle_in_transaction_session_timeout = '10s'");
        lock.setObject(1, payoutId);
        lock.executeQuery().close();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void releaseItemLock() throws SQLException {
    if (itemLock != null) {
      itemLock.close();
      itemLock = null;
    }
  }

  private void expireLease() {
    jdbc.update("UPDATE settlement.payout_jobs SET lease_until = now() - interval '1 second'");
  }

  private Map<String, Object> payout() {
    return jdbc.queryForMap("SELECT * FROM settlement.payouts WHERE id = ?", payoutId);
  }

  private Map<String, Object> job() {
    return jdbc.queryForMap("SELECT * FROM settlement.payout_jobs WHERE payout_id = ?", payoutId);
  }

  private String batchStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.settlement_batches WHERE id = ?", String.class, batchId);
  }

  private List<String> itemStatuses() {
    return jdbc.queryForList(
        "SELECT status FROM settlement.settlement_items WHERE payout_id = ?",
        String.class,
        payoutId);
  }

  private List<String> reservationStatuses() {
    return jdbc.queryForList(
        "SELECT status FROM settlement.settlement_reservations WHERE payout_id = ?",
        String.class,
        payoutId);
  }

  /** 미리 넣은 결과를 순서대로 돌려주고 받은 요청을 기록한다. */
  static class ScriptedGateway implements PayoutGateway {
    final Deque<Result> executeResults = new ArrayDeque<>();
    final Deque<Result> lookupResults = new ArrayDeque<>();
    final List<PayoutRequest> executed = new ArrayList<>();
    final List<PayoutRequest> lookedUp = new ArrayList<>();
    Runnable beforeResult = () -> {};

    @Override
    public Result execute(PayoutRequest request) {
      executed.add(request);
      beforeResult.run();
      return executeResults.removeFirst();
    }

    @Override
    public Result lookup(PayoutRequest request) {
      lookedUp.add(request);
      beforeResult.run();
      return lookupResults.removeFirst();
    }

    void reset() {
      executeResults.clear();
      lookupResults.clear();
      executed.clear();
      lookedUp.clear();
      beforeResult = () -> {};
    }
  }

  @TestConfiguration
  static class Gateway {
    @Bean
    @Primary
    ScriptedGateway scriptedGateway() {
      return new ScriptedGateway();
    }
  }
}
