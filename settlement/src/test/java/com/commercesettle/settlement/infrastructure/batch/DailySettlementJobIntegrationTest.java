package com.commercesettle.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 Spring Batch 일배치의 판매자 단위 커밋·건너뛰기·재실행·재시작을 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false"
    })
@Testcontainers
class DailySettlementJobIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired DailySettlementLauncher launcher;
  @Autowired SettlementItemRepository items;
  @Autowired JdbcTemplate jdbc;
  @Autowired FakeContextReader contexts;
  @Autowired DataSource dataSource;

  @AfterEach
  void deleteBusinessRows() {
    for (String table :
        List.of(
            "settlement_items",
            "settlement_batches",
            "payouts",
            "payout_jobs",
            "settlement_reservations")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
    contexts.reset();
  }

  @Test
  @DisplayName("마감 전 지급 대상만 선점해 배치·지급·지급 작업·지급 예약을 한 번에 만든다")
  void reservesPayableItemsBeforeCutoff() {
    LocalDate batchDate = LocalDate.of(2026, 9, 1);
    Instant cutoff = SettlementBatch.cutoffOf(batchDate);
    UUID seller = UUID.randomUUID();
    SettlementItem justBefore = payable(seller, 10_000, cutoff.minusNanos(1_000));
    SettlementItem earlier = payable(seller, 20_000, cutoff.minusSeconds(86_400 * 3));
    SettlementItem atCutoff = payable(seller, 30_000, cutoff);
    SettlementItem refundReserved = payable(seller, 40_000, cutoff.minusSeconds(60));
    refundReserved.reserveRefund(cutoff.minusSeconds(30));
    items.save(refundReserved);

    BatchStatus status = launcher.run(batchDate);

    assertThat(status).isEqualTo(BatchStatus.COMPLETED);
    Map<String, Object> batch = single("settlement_batches WHERE seller_id = ?", seller);
    assertThat(batch)
        .containsEntry("status", "RESERVED")
        .containsEntry("net_amount", 8_500L + 17_000L)
        .containsEntry("policy_version", "delivery-daily-v1");
    Map<String, Object> payout = single("payouts WHERE batch_id = ?", batch.get("id"));
    assertThat(payout)
        .containsEntry("status", "PENDING")
        .containsEntry("amount", 25_500L)
        .containsEntry("recipient_account_ref", "account-" + seller)
        .containsEntry("funding_account_ref", "payout-funding-default")
        .containsEntry("scenario_version", "payout-normal-v1");
    Map<String, Object> job = single("payout_jobs WHERE payout_id = ?", payout.get("id"));
    assertThat(job)
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("business_key", payout.get("business_key"));
    assertThat(statusOf(justBefore)).isEqualTo("PAYOUT_RESERVED");
    assertThat(statusOf(earlier)).isEqualTo("PAYOUT_RESERVED");
    assertThat(statusOf(atCutoff)).isEqualTo("PAYABLE");
    assertThat(statusOf(refundReserved)).isEqualTo("REFUND_RESERVED");
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM settlement.settlement_reservations"
                    + " WHERE payout_id = ? AND kind = 'PAYOUT' AND status = 'ACTIVE'",
                Long.class,
                payout.get("id")))
        .containsExactlyInAnyOrder(8_500L, 17_000L);
  }

  @Test
  @DisplayName("같은 날짜로 다시 실행해도 배치·지급·예약을 늘리지 않고 닫힌 배치에 항목을 더하지 않는다")
  void rerunDoesNotDuplicateOrExtendBatch() {
    LocalDate batchDate = LocalDate.of(2026, 9, 2);
    Instant cutoff = SettlementBatch.cutoffOf(batchDate);
    UUID seller = UUID.randomUUID();
    payable(seller, 10_000, cutoff.minusSeconds(60));
    launcher.run(batchDate);
    SettlementItem late = payable(seller, 20_000, cutoff.minusSeconds(30));

    BatchStatus rerun = launcher.run(batchDate);

    assertThat(rerun).isEqualTo(BatchStatus.COMPLETED);
    assertThat(count("settlement_batches")).isEqualTo(1);
    assertThat(count("payouts")).isEqualTo(1);
    assertThat(count("payout_jobs")).isEqualTo(1);
    assertThat(count("settlement_reservations")).isEqualTo(1);
    assertThat(statusOf(late)).isEqualTo("PAYABLE");
  }

  @Test
  @DisplayName("지급 전 검증에 실패한 판매자는 선점까지 롤백하고 건너뛰며 다른 판매자는 커밋한다")
  void skipsSellerRejectedByPayoutValidation() {
    LocalDate batchDate = LocalDate.of(2026, 9, 3);
    Instant cutoff = SettlementBatch.cutoffOf(batchDate);
    UUID rejected = UUID.randomUUID();
    UUID accepted = UUID.randomUUID();
    SettlementItem rejectedItem = payable(rejected, 10_000, cutoff.minusSeconds(60));
    payable(accepted, 20_000, cutoff.minusSeconds(60));
    contexts.notAllowed.add(rejected);

    BatchStatus status = launcher.run(batchDate);

    assertThat(status).isEqualTo(BatchStatus.COMPLETED);
    assertThat(count("settlement_batches WHERE seller_id = ?", accepted)).isEqualTo(1);
    assertThat(count("settlement_batches WHERE seller_id = ?", rejected)).isZero();
    assertThat(statusOf(rejectedItem)).isEqualTo("PAYABLE");
    assertThat(count("settlement_reservations WHERE item_id = ?", rejectedItem.getId())).isZero();
  }

  @Test
  @DisplayName("항목 잠금 대기가 상한을 넘은 판매자는 롤백하고 건너뛰며 뒤의 판매자는 커밋한다")
  void skipsSellerWhoseItemsAreLocked() throws SQLException {
    LocalDate batchDate = LocalDate.of(2026, 9, 6);
    Instant cutoff = SettlementBatch.cutoffOf(batchDate);
    // reader가 seller_id 순으로 읽으므로 잠긴 판매자를 먼저 처리한다.
    UUID locked = new UUID(0, 1);
    UUID accepted = new UUID(0, 2);
    SettlementItem lockedItem = payable(locked, 10_000, cutoff.minusSeconds(60));
    SettlementItem acceptedItem = payable(accepted, 20_000, cutoff.minusSeconds(60));

    BatchStatus status;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (Statement timeout = holder.createStatement();
          PreparedStatement lock =
              holder.prepareStatement(
                  "SELECT id FROM settlement.settlement_items WHERE id = ? FOR UPDATE")) {
        // lock_timeout이 빠지는 회귀에도 테스트가 멈추지 않고 잠금이 풀려 단언에서 실패하게 한다.
        timeout.execute("SET idle_in_transaction_session_timeout = '10s'");
        lock.setObject(1, lockedItem.getId());
        lock.executeQuery().close();
        status = launcher.run(batchDate);
      } finally {
        holder.rollback();
      }
    }

    assertThat(status).isEqualTo(BatchStatus.COMPLETED);
    assertThat(statusOf(lockedItem)).isEqualTo("PAYABLE");
    assertThat(count("settlement_items WHERE id = ? AND payout_id IS NULL", lockedItem.getId()))
        .isEqualTo(1);
    assertThat(count("settlement_batches WHERE seller_id = ?", locked)).isZero();
    assertThat(count("payouts WHERE seller_id = ?", locked)).isZero();
    assertThat(
            count(
                "payout_jobs j JOIN settlement.payouts p ON p.id = j.payout_id"
                    + " WHERE p.seller_id = ?",
                locked))
        .isZero();
    assertThat(count("settlement_reservations WHERE item_id = ?", lockedItem.getId())).isZero();
    assertThat(count("settlement_batches WHERE seller_id = ?", accepted)).isEqualTo(1);
    assertThat(count("payouts WHERE seller_id = ?", accepted)).isEqualTo(1);
    assertThat(count("payout_jobs")).isEqualTo(1);
    assertThat(statusOf(acceptedItem)).isEqualTo("PAYOUT_RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT s.write_skip_count FROM settlement.batch_step_execution s"
                    + " ORDER BY s.step_execution_id DESC LIMIT 1",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("예상 밖 오류로 실패한 뒤 재시작하면 이미 커밋한 판매자는 다시 처리하지 않는다")
  void restartContinuesAfterCommittedSellers() {
    LocalDate batchDate = LocalDate.of(2026, 9, 4);
    Instant cutoff = SettlementBatch.cutoffOf(batchDate);
    UUID first = new UUID(0, 1);
    UUID second = new UUID(0, 2);
    payable(first, 10_000, cutoff.minusSeconds(60));
    SettlementItem secondItem = payable(second, 20_000, cutoff.minusSeconds(60));
    contexts.failOnce.add(second);

    BatchStatus failed = launcher.run(batchDate);

    assertThat(failed).isEqualTo(BatchStatus.FAILED);
    assertThat(count("settlement_batches WHERE seller_id = ?", first)).isEqualTo(1);
    assertThat(statusOf(secondItem)).isEqualTo("PAYABLE");

    BatchStatus restarted = launcher.run(batchDate);

    assertThat(restarted).isEqualTo(BatchStatus.COMPLETED);
    assertThat(count("settlement_batches WHERE seller_id = ?", second)).isEqualTo(1);
    assertThat(contexts.calls.get(first)).isEqualTo(1);
    assertThat(statusOf(secondItem)).isEqualTo("PAYOUT_RESERVED");
  }

  @Test
  @DisplayName("대상이 없으면 배치를 만들지 않는다")
  void createsNothingWithoutPayableItems() {
    BatchStatus status = launcher.run(LocalDate.of(2026, 9, 5));

    assertThat(status).isEqualTo(BatchStatus.COMPLETED);
    assertThat(count("settlement_batches")).isZero();
  }

  @Test
  @DisplayName("마감이 지나지 않은 날짜는 실행하지 않는다")
  void rejectsDateBeforeCutoff() {
    assertThatThrownBy(() -> launcher.run(LocalDate.now().plusDays(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private SettlementItem payable(UUID seller, long base, Instant readyAt) {
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
    item.markPayable(readyAt, readyAt.minusSeconds(300), readyAt);
    return items.save(item);
  }

  private String statusOf(SettlementItem item) {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.settlement_items WHERE id = ?", String.class, item.getId());
  }

  private Map<String, Object> single(String tableAndWhere, Object arg) {
    return jdbc.queryForMap("SELECT * FROM settlement." + tableAndWhere, arg);
  }

  private long count(String tableAndWhere, Object... args) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement." + tableAndWhere, Long.class, args);
  }

  /** 판매자별로 지급 불가·일회성 장애를 주입하고 호출 횟수를 센다. 계정은 판매자마다 하나다. */
  static class FakeContextReader implements ProductSettlementContextReader {
    final Set<UUID> notAllowed = ConcurrentHashMap.newKeySet();
    final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();
    final Map<UUID, Integer> calls = new ConcurrentHashMap<>();

    @Override
    public List<ProductSettlementContext> read(UUID sellerId, Set<UUID> productIds) {
      calls.merge(sellerId, 1, Integer::sum);
      if (failOnce.remove(sellerId)) {
        throw new IllegalStateException("injected failure");
      }
      return new HashSet<>(productIds)
          .stream()
              .map(
                  productId ->
                      new ProductSettlementContext(
                          productId,
                          sellerId,
                          !notAllowed.contains(sellerId),
                          "account-" + sellerId,
                          1))
              .toList();
    }

    void reset() {
      notAllowed.clear();
      failOnce.clear();
      calls.clear();
    }
  }

  @TestConfiguration
  static class Contexts {
    @Bean
    @Primary
    FakeContextReader fakeContextReader() {
      return new FakeContextReader();
    }
  }
}
