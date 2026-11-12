package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 같은 지급 가능 항목에 환불 예약과 일배치 지급 선점을 동시에 실행해, 둘 중 하나만 성공하고 활성 예약이 하나 이하인지 반복해 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundPayoutExclusionIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final long BASE = 10_000;
  private static final long FEE = BASE * 15 / 100;

  @Autowired RefundEventService refunds;
  @Autowired SettlementItemRepository items;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @AfterEach
  void cleanUp() {
    pool.shutdownNow();
    for (String table : List.of("inbox", "outbox", "settlement_items", "settlement_reservations")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @RepeatedTest(10)
  @DisplayName("환불 예약과 지급 선점이 동시에 같은 항목을 다투면 하나만 이기고 진 쪽은 상태를 바꾸지 않는다")
  void onlyOneReservationWins() throws Exception {
    UUID paymentId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    Instant readyAt = Instant.now().minusSeconds(3_600);
    SettlementItem item =
        SettlementItem.create(
            paymentId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            sellerId,
            new SettlementAmounts(BASE, 0, BASE, BASE, 0, FEE, BASE - FEE),
            0,
            readyAt);
    item.markPayable(readyAt, readyAt, readyAt);
    items.save(item);
    var request =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "RefundReservationRequested",
            1,
            OffsetDateTime.now(),
            paymentId,
            0,
            new RefundReservationRequestedPayload(UUID.randomUUID(), paymentId, BASE - FEE));
    byte[] body = mapper.writeValueAsBytes(request);
    CyclicBarrier start = new CyclicBarrier(2);

    Future<?> refund =
        pool.submit(
            () -> {
              await(start);
              refunds.receiveReservationRequested(
                  request, new Source("commerce-settle.payment.v1", 0, 0, body));
              return null;
            });
    Future<?> payout =
        pool.submit(
            () -> {
              await(start);
              return transactions.execute(
                  status ->
                      items.reserveForPayout(
                          sellerId, Instant.now(), UUID.randomUUID(), Instant.now()));
            });
    boolean refundLocked = finished(refund);
    boolean payoutLocked = finished(payout);

    String status =
        jdbc.queryForObject(
            "SELECT status FROM settlement.settlement_items WHERE id = ?",
            String.class,
            item.getId());
    List<String> active =
        jdbc.queryForList(
            "SELECT kind FROM settlement.settlement_reservations WHERE status = 'ACTIVE'",
            String.class);
    assertThat(status).isIn("REFUND_RESERVED", "PAYOUT_RESERVED");
    assertThat(active).hasSize(1);
    if (status.equals("REFUND_RESERVED")) {
      assertThat(active).containsExactly("REFUND");
    } else {
      assertThat(active).containsExactly("PAYOUT");
      // 환불 쪽이 잠금 대기 상한에 걸리지 않았다면 지급 배정을 사유로 거절했어야 한다.
      if (!refundLocked) {
        assertThat(
                new String(
                    jdbc.queryForObject("SELECT raw_body FROM settlement.outbox", byte[].class),
                    java.nio.charset.StandardCharsets.UTF_8))
            .contains("\"reasonCode\":\"PAYOUT_RESERVED\"");
      }
    }
    assertThat(refundLocked && payoutLocked).as("둘 다 잠금 대기로 실패하지 않는다").isFalse();
  }

  /** 정상 종료면 false, 잠금 대기 상한으로 롤백됐으면 true. 그 밖의 예외는 실패다. */
  private static boolean finished(Future<?> task) throws Exception {
    try {
      task.get(30, TimeUnit.SECONDS);
      return false;
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof CannotAcquireLockException) {
        return true;
      }
      throw e;
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
