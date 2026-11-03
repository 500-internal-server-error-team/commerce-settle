package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.EventAdmissionClosedException;
import com.commercesettle.commerce.domain.exception.EventAdmissionClosedException.Reason;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.model.EventAdmission;
import com.commercesettle.commerce.domain.model.InventoryReservation.Outcome;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.SaleEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 주문 트랜잭션에 참여한 선착순 예약·확정·해제·재시도·복원의 이벤트 수량·참여·재고 커밋 결과와 동시 참여 제한을 확인한다. */
// 인증 설정은 필수 기동 값이다. 기간 처리는 직접 호출하므로 스케줄러를 끈다.
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.sale-events.scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=event-participation-test-hmac-key-32b",
      "app.auth.password-reset.delivery-key=event-participation-test-delivery-key-32b",
      "app.address.encryption-key=event-participation-test-address-key-32b",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventParticipationIntegrationTest {
  private static final ShiftedClock CLOCK = new ShiftedClock();

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired InventoryReservationService reservations;
  @Autowired SaleEventService events;
  @Autowired ProductCatalogService catalog;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @TestBean Clock clock;

  static Clock clock() {
    return CLOCK;
  }

  @AfterEach
  void resetClock() {
    CLOCK.shift(Duration.ZERO);
  }

  @Test
  @DisplayName("주문 트랜잭션 밖에서 호출하면 거절하고, 주문이 롤백되면 이벤트 수량·참여·예약도 남지 않는다")
  void joinsCallerTransactionOnly() {
    Fixture fixture = openEvent(5);

    assertThatThrownBy(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalTransactionStateException.class);
    tx.executeWithoutResult(
        status -> {
          reserve(fixture, UUID.randomUUID(), UUID.randomUUID());
          status.setRollbackOnly();
        });

    assertSlots(fixture, 0, 0);
    assertThat(count("event_participations where event_id = ?", fixture.eventId())).isZero();
    assertThat(count("inventory_reservations where event_id = ?", fixture.eventId())).isZero();
  }

  @Test
  @DisplayName("예약은 이벤트 R과 참여·예약 행을 함께 커밋하고 재고 행은 바꾸지 않으며, 같은 주문 재처리는 다시 반영하지 않는다")
  void reservesSlotWithParticipation() {
    Fixture fixture = openEvent(5);
    UUID orderId = UUID.randomUUID();
    UUID cycle = UUID.randomUUID();

    Outcome first =
        inOrder(
            () ->
                reservations.reserveForEvent(
                    orderId, fixture.eventId(), UUID.randomUUID(), cycle, expires()));
    Outcome replay = inOrder(() -> reserve(fixture, orderId, cycle));

    assertThat(first).isEqualTo(Outcome.APPLIED);
    assertThat(replay).isEqualTo(Outcome.ALREADY_APPLIED);
    assertThatThrownBy(() -> inOrder(() -> reserve(fixture, orderId, UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
    assertSlots(fixture, 0, 1);
    assertThat(participationStatus(orderId)).isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForMap(
                "select event_id, quantity, status from commerce.inventory_reservations"
                    + " where order_id = ?",
                orderId))
        .containsEntry("event_id", fixture.eventId())
        .containsEntry("quantity", 1)
        .containsEntry("status", "RESERVED");
    assertInventory(fixture, 5, 5, 0);
    assertThat(
            count(
                "inventory_movements where product_id = ? and operation like 'ORDER_%'",
                fixture.productId()))
        .isZero();
  }

  @Test
  @DisplayName("남은 수량보다 많은 동시 참여는 남은 수량만큼만 성공한다")
  void doesNotOverbookUnderConcurrentParticipation() throws Exception {
    Fixture fixture = openEvent(5);

    List<Object> results =
        runConcurrently(
            12, () -> inOrder(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID())));

    assertThat(results).filteredOn(Outcome.APPLIED::equals).hasSize(5);
    assertThat(results)
        .filteredOn(EventAdmissionClosedException.class::isInstance)
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .hasSize(7)
        .containsOnly(Reason.NO_REMAINING);
    assertSlots(fixture, 0, 5);
    assertThat(count("event_participations where event_id = ?", fixture.eventId())).isEqualTo(5);
  }

  @Test
  @DisplayName("같은 구매자가 다른 주문으로 동시에 참여하면 하나만 성공하고 나머지는 수량을 잡지 않는다")
  void allowsOneParticipationPerBuyerUnderConcurrency() throws Exception {
    Fixture fixture = openEvent(5);
    UUID buyer = UUID.randomUUID();

    List<Object> results =
        runConcurrently(
            6,
            () ->
                inOrder(
                    () ->
                        reservations.reserveForEvent(
                            UUID.randomUUID(),
                            fixture.eventId(),
                            buyer,
                            UUID.randomUUID(),
                            expires())));

    assertThat(results).filteredOn(Outcome.APPLIED::equals).hasSize(1);
    assertThat(results)
        .filteredOn(EventAdmissionClosedException.class::isInstance)
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .hasSize(5)
        .containsOnly(Reason.ALREADY_PARTICIPATED);
    assertSlots(fixture, 0, 1);
  }

  @Test
  @DisplayName("확정·해제·재시도·환불을 거치면 S·R·참여·재고가 함께 바뀌고 재처리는 한 번만 반영한다")
  void followsConsumeReleaseRetryAndRefund() {
    Fixture fixture = openEvent(5);
    UUID paid = UUID.randomUUID();
    UUID declined = UUID.randomUUID();
    UUID paidCycle = UUID.randomUUID();
    UUID firstCycle = UUID.randomUUID();
    UUID retryCycle = UUID.randomUUID();
    inOrder(() -> reserve(fixture, paid, paidCycle));
    inOrder(() -> reserve(fixture, declined, firstCycle));

    Outcome consumed = inOrder(() -> reservations.consume(paid, paidCycle));
    Outcome consumedAgain = inOrder(() -> reservations.consume(paid, paidCycle));
    inOrder(() -> reservations.release(declined, firstCycle));
    assertThat(participationStatus(declined)).isEqualTo("RELEASED");
    Outcome retried = inOrder(() -> reservations.reserveAgain(declined, retryCycle, expires()));
    Outcome late = inOrder(() -> reservations.release(declined, firstCycle));
    Outcome refunded = inOrder(() -> reservations.restoreForRefund(paid, UUID.randomUUID()));
    Outcome returned = inOrder(() -> reservations.restoreForReturn(paid, UUID.randomUUID()));

    assertThat(List.of(consumed, consumedAgain, retried, late, refunded, returned))
        .containsExactly(
            Outcome.APPLIED,
            Outcome.ALREADY_APPLIED,
            Outcome.APPLIED,
            Outcome.STALE_CYCLE,
            Outcome.APPLIED,
            Outcome.ALREADY_APPLIED);
    assertSlots(fixture, 1, 1);
    assertThat(participationStatus(paid)).isEqualTo("REFUNDED");
    assertThat(participationStatus(declined)).isEqualTo("RESERVED");
    assertInventory(fixture, 6, 4, 0);
    assertThat(
            jdbc.queryForList(
                "select operation from commerce.inventory_movements where product_id = ?"
                    + " and operation not in ('SELLER_ADJUSTMENT', 'EVENT_ALLOCATE')",
                String.class,
                fixture.productId()))
        .containsExactlyInAnyOrder("ORDER_CONSUME", "REFUND_RESTORE");
  }

  @Test
  @DisplayName("마지막 수량이 확정되면 매진 종료하고 이후 참여는 거절한다")
  void sellsOutOnLastConfirmation() {
    Fixture fixture = openEvent(1);
    UUID orderId = UUID.randomUUID();
    UUID cycle = UUID.randomUUID();
    inOrder(() -> reserve(fixture, orderId, cycle));

    inOrder(() -> reservations.consume(orderId, cycle));

    assertThat(
            jdbc.queryForMap(
                "select status, closed_at is not null as closed from commerce.sale_events"
                    + " where id = ?",
                fixture.eventId()))
        .containsEntry("status", "SOLD_OUT")
        .containsEntry("closed", true);
    assertThatThrownBy(() -> inOrder(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID())))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NO_REMAINING);
    assertInventory(fixture, 9, 0, 1);
  }

  @Test
  @DisplayName("종료 뒤 해제는 남겨 둔 배정분을 가용 재고로 돌리고, 종료된 이벤트에는 새로 참여할 수 없다")
  void releaseAfterCloseReturnsAllocation() {
    Fixture fixture = openEvent(5);
    UUID orderId = UUID.randomUUID();
    UUID cycle = UUID.randomUUID();
    inOrder(() -> reserve(fixture, orderId, cycle));
    events.close(fixture.sellerId(), fixture.eventId(), key(), version(fixture), "조기 종료");
    assertInventory(fixture, 9, 1, 0);

    inOrder(() -> reservations.release(orderId, cycle));

    assertSlots(fixture, 0, 0);
    assertInventory(fixture, 10, 0, 0);
    assertThat(
            count(
                "inventory_movements where operation = 'ORDER_RELEASE' and business_ref = ?",
                cycle))
        .isEqualTo(1);
    assertThatThrownBy(() -> inOrder(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID())))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NOT_OPEN);
  }

  @Test
  @DisplayName("주문 트랜잭션이 먼저 읽은 이벤트가 그 뒤 조기 종료됐으면 신청 기간이 아니라고 거절한다")
  void reportsNotOpenWhenEventClosedAfterRead() {
    Fixture fixture = openEvent(5);
    long version = version(fixture);

    assertThatThrownBy(
            () ->
                inOrder(
                    () -> {
                      events.publishedEvent(fixture.eventId());
                      CompletableFuture.runAsync(
                              () ->
                                  events.close(
                                      fixture.sellerId(),
                                      fixture.eventId(),
                                      key(),
                                      version,
                                      "조기 종료"))
                          .join();
                      return reserve(fixture, UUID.randomUUID(), UUID.randomUUID());
                    }))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NOT_OPEN);
    assertSlots(fixture, 0, 0);
  }

  @Test
  @DisplayName("신청 기간은 시작 시각을 포함하고 종료 시각을 포함하지 않으며 신규 참여·재시도·게이지가 같은 경계를 쓴다")
  void admitsFromStartUntilJustBeforeEnd() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    Instant start = hoursFromNow(1);
    Instant end = start.plus(Duration.ofHours(1));
    SaleEvent event =
        events.publish(seller, register(seller, product, 5, start, end).getId(), key(), 0);
    Fixture fixture = new Fixture(seller, product, event.getId());
    UUID declined = UUID.randomUUID();
    UUID cycle = UUID.randomUUID();

    CLOCK.fixAt(start.minus(1, ChronoUnit.MICROS));
    Reason beforeStart =
        rejectedReason(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID()));
    CLOCK.fixAt(start);
    Outcome atStart = inOrder(() -> reserve(fixture, declined, cycle));
    inOrder(() -> reservations.release(declined, cycle));
    CLOCK.fixAt(end.minus(1, ChronoUnit.MICROS));
    Outcome justBeforeEnd = inOrder(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID()));
    CLOCK.fixAt(end);
    Reason atEnd = rejectedReason(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID()));
    Reason retryAtEnd =
        rejectedReason(() -> reservations.reserveAgain(declined, UUID.randomUUID(), expires()));

    assertThat(List.of(atStart, justBeforeEnd)).containsOnly(Outcome.APPLIED);
    assertThat(List.of(beforeStart, atEnd, retryAtEnd)).containsOnly(Reason.NOT_OPEN);
    assertThat(events.gauge(fixture.eventId()).gauge().admission())
        .isEqualTo(EventAdmission.CLOSED);
    assertSlots(fixture, 0, 1);
  }

  @Test
  @DisplayName("조기 종료 뒤 도착한 결제 확정도 반영해 배정분을 판매로 옮기고 반환 이력을 더 만들지 않는다")
  void confirmsReservationAfterEarlyClose() {
    Fixture fixture = openEvent(5);
    UUID orderId = UUID.randomUUID();
    UUID cycle = UUID.randomUUID();
    inOrder(() -> reserve(fixture, orderId, cycle));
    events.close(fixture.sellerId(), fixture.eventId(), key(), version(fixture), "조기 종료");

    Outcome consumed = inOrder(() -> reservations.consume(orderId, cycle));

    assertThat(consumed).isEqualTo(Outcome.APPLIED);
    assertSlots(fixture, 1, 0);
    assertInventory(fixture, 9, 0, 1);
    assertThat(participationStatus(orderId)).isEqualTo("CONFIRMED");
    assertThat(
            count(
                "inventory_movements where operation = 'EVENT_RETURN' and business_ref = ?",
                fixture.eventId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("조기 종료와 참여가 동시에 오면 종료 전에 커밋한 예약만 남고 배정분은 그 예약 수와 같다")
  void keepsAllocationEqualToReservationsWhenCloseRacesParticipation() throws Exception {
    Fixture fixture = openEvent(5);
    long version = version(fixture);
    AtomicInteger next = new AtomicInteger();

    List<Object> results =
        runConcurrently(
            9,
            () ->
                next.getAndIncrement() == 0
                    ? events.close(fixture.sellerId(), fixture.eventId(), key(), version, "조기 종료")
                    : inOrder(() -> reserve(fixture, UUID.randomUUID(), UUID.randomUUID())));

    long reserved = results.stream().filter(Outcome.APPLIED::equals).count();
    assertThat(results).filteredOn(SaleEvent.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(EventAdmissionClosedException.class::isInstance)
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isSubsetOf(Reason.NOT_OPEN, Reason.NO_REMAINING);
    assertSlots(fixture, 0, reserved);
    assertInventory(fixture, 10 - reserved, reserved, 0);
    assertThat(count("event_participations where event_id = ?", fixture.eventId()))
        .isEqualTo(reserved);
  }

  @Test
  @DisplayName("시작 전 이벤트는 신청 기간이 아니라고, 미공개 이벤트는 없는 이벤트로 거절한다")
  void rejectsNotStartedAndUnpublishedEvents() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent scheduled =
        events.publish(
            seller,
            register(seller, product, 3, hoursFromNow(1), hoursFromNow(2)).getId(),
            key(),
            0);
    SaleEvent draft = register(seller, product, 3, hoursFromNow(3), hoursFromNow(4));

    assertThatThrownBy(
            () ->
                inOrder(
                    () ->
                        reservations.reserveForEvent(
                            UUID.randomUUID(),
                            scheduled.getId(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            expires())))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NOT_OPEN);
    assertThatThrownBy(
            () ->
                inOrder(
                    () ->
                        reservations.reserveForEvent(
                            UUID.randomUUID(),
                            draft.getId(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            expires())))
        .isInstanceOf(SaleEventNotFoundException.class);
    assertThat(
            count(
                "event_participations where event_id in (?, ?)", scheduled.getId(), draft.getId()))
        .isZero();
  }

  @Test
  @DisplayName("스케줄러가 열기 전에도 시작 시각이 지나면 게이지·신규 참여·재시도가 같이 받고, 저장 상태·버전은 바꾸지 않는다")
  void admitsStartedEventBeforeSchedulerOpensIt() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent scheduled =
        events.publish(
            seller,
            register(seller, product, 3, hoursFromNow(1), hoursFromNow(2)).getId(),
            key(),
            0);
    Fixture fixture = new Fixture(seller, product, scheduled.getId());
    UUID orderId = UUID.randomUUID();
    UUID firstCycle = UUID.randomUUID();
    long publishedVersion = version(fixture);
    CLOCK.shift(Duration.ofHours(1));

    EventAdmission admission = events.gauge(fixture.eventId()).gauge().admission();
    Outcome reserved = inOrder(() -> reserve(fixture, orderId, firstCycle));
    inOrder(() -> reservations.release(orderId, firstCycle));
    Outcome retried =
        inOrder(() -> reservations.reserveAgain(orderId, UUID.randomUUID(), expires()));

    assertThat(admission).isEqualTo(EventAdmission.OPEN);
    assertThat(List.of(reserved, retried)).containsExactly(Outcome.APPLIED, Outcome.APPLIED);
    assertSlots(fixture, 0, 1);
    assertThat(
            jdbc.queryForMap(
                "select status, version from commerce.sale_events where id = ?", fixture.eventId()))
        .containsEntry("status", "SCHEDULED")
        .containsEntry("version", publishedVersion);
  }

  /** 주문 트랜잭션에서 실행해 참여 거절 사유를 돌려준다. */
  private Reason rejectedReason(Supplier<Outcome> work) {
    try {
      inOrder(work);
    } catch (EventAdmissionClosedException e) {
      return e.reason();
    }
    throw new AssertionError("admission was not rejected");
  }

  private Outcome reserve(Fixture fixture, UUID orderId, UUID cycle) {
    return reservations.reserveForEvent(
        orderId, fixture.eventId(), UUID.randomUUID(), cycle, expires());
  }

  /** 주문 응용 서비스의 트랜잭션을 대신한다. */
  private <T> T inOrder(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  /** 가용 10개 상품에 이미 시작한 이벤트를 공개한다. */
  private Fixture openEvent(long quota) {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent draft = register(seller, product, quota, hoursFromNow(-1), hoursFromNow(1));
    events.publish(seller, draft.getId(), key(), 0);
    return new Fixture(seller, product, draft.getId());
  }

  private UUID stockedProduct(UUID seller, long quantity) {
    UUID product = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();
    catalog.adjustAvailable(seller, product, key(), quantity, 0, "입고");
    return product;
  }

  private SaleEvent register(
      UUID seller, UUID product, long quota, Instant startsAt, Instant endsAt) {
    return events.register(seller, key(), product, "주말 특가", 10_000, quota, startsAt, endsAt);
  }

  private long version(Fixture fixture) {
    return events.sellerEvent(fixture.sellerId(), fixture.eventId()).getVersion();
  }

  private void assertSlots(Fixture fixture, long sold, long reserved) {
    Map<String, Object> counts =
        jdbc.queryForMap(
            "select sold_count, reserved_count from commerce.sale_events where id = ?",
            fixture.eventId());
    assertThat(counts).containsEntry("sold_count", sold).containsEntry("reserved_count", reserved);
  }

  private void assertInventory(Fixture fixture, long available, long allocated, long sold) {
    assertThat(
            jdbc.queryForMap(
                "select available_qty, reserved_qty, event_allocated_qty, sold_qty"
                    + " from commerce.product_inventory where product_id = ?",
                fixture.productId()))
        .containsEntry("available_qty", available)
        .containsEntry("reserved_qty", 0L)
        .containsEntry("event_allocated_qty", allocated)
        .containsEntry("sold_qty", sold);
  }

  private String participationStatus(UUID orderId) {
    return jdbc.queryForObject(
        "select status from commerce.event_participations where order_id = ?",
        String.class,
        orderId);
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  private static Instant expires() {
    return CLOCK.instant().plus(Duration.ofMinutes(10));
  }

  private static Instant hoursFromNow(long hours) {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(hours));
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Fixture(UUID sellerId, UUID productId, UUID eventId) {}

  // 시스템 시각에 테스트가 정한 만큼 더한 시각, 또는 고정한 시각을 돌려준다. 스케줄러는 꺼져 있어 시각만 옮긴다.
  private static final class ShiftedClock extends Clock {
    private volatile Duration offset = Duration.ZERO;
    private volatile Instant fixed;

    void shift(Duration offset) {
      this.offset = offset;
      this.fixed = null;
    }

    /** 경계 시각을 정확히 재현하도록 시각을 고정한다. shift로 되돌린다. */
    void fixAt(Instant instant) {
      this.fixed = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.offset(Clock.system(zone), offset);
    }

    @Override
    public Instant instant() {
      Instant at = fixed;
      return at != null ? at : Instant.now().plus(offset);
    }
  }
}
