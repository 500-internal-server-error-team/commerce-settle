package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException.Reason;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.model.EventAdmission;
import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.model.SaleEventStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 이벤트 공개·종료의 재고 배정·반환과 상품 변경 차단, 같은 재고 행 잠금 아래의 기간 중복 검사를 확인한다. */
// 인증 설정은 필수 기동 값이다. 기간 처리는 시각을 넘겨 직접 호출하므로 스케줄러를 끈다.
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.sale-events.scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=sale-event-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=sale-event-test-delivery-key-32bytes",
      "app.address.encryption-key=sale-event-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123"
    })
@Testcontainers
class SaleEventIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired SaleEventService events;
  @Autowired ProductCatalogService catalog;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("공개는 가용 감소·배정 증가·배정 이력·예정 상태를 함께 커밋하고 같은 키 재요청은 다시 배정하지 않는다")
  void publishesAndAllocatesOnce() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 25);
    SaleEvent draft = register(seller, product, 10, hoursFromNow(1), hoursFromNow(2));
    String key = key();

    SaleEvent published = events.publish(seller, draft.getId(), key, 0);
    SaleEvent replay = events.publish(seller, draft.getId(), key, 0);

    assertThat(published.getStatus()).isEqualTo(SaleEventStatus.SCHEDULED);
    assertThat(published.getVersion()).isEqualTo(1);
    assertThat(replay.getVersion()).isEqualTo(1);
    ProductInventory inventory = catalog.inventory(seller, product);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(15);
    assertThat(inventory.getEventAllocatedQuantity()).isEqualTo(10);
    Map<String, Object> movement =
        jdbc.queryForMap(
            "select * from commerce.inventory_movements where operation = 'EVENT_ALLOCATE'"
                + " and business_ref = ?",
            draft.getId());
    assertThat(movement)
        .containsEntry("available_delta", -10L)
        .containsEntry("allocated_delta", 10L)
        .containsEntry("reserved_delta", 0L)
        .containsEntry("sold_delta", 0L);
    assertThat(count("sale_events where id = ? and published_at is not null", draft.getId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("가용 재고보다 많은 수량은 공개하지 않고 이벤트·재고·이력·멱등 기록을 남기지 않는다")
  void rejectsPublishingBeyondAvailableWithoutSideEffects() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 9);
    SaleEvent draft = register(seller, product, 10, hoursFromNow(1), hoursFromNow(2));

    assertThatThrownBy(() -> events.publish(seller, draft.getId(), key(), 0))
        .isInstanceOf(InsufficientInventoryException.class);

    assertThat(events.sellerEvent(seller, draft.getId()).getStatus())
        .isEqualTo(SaleEventStatus.DRAFT);
    assertThat(catalog.inventory(seller, product).getAvailableQuantity()).isEqualTo(9);
    assertThat(count("inventory_movements where business_ref = ?", draft.getId())).isZero();
    assertThat(
            count(
                "idempotency_records where actor_id = ? and operation = 'EVENT_PUBLISH'",
                seller.toString()))
        .isZero();
  }

  @Test
  @DisplayName("판매 중이 아닌 상품과 기간이 겹치는 공개 이벤트는 거절하고, 겹치지 않거나 조기 종료한 이벤트와는 공개한다")
  void checksProductStatusAndPeriodOverlap() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 100);
    SaleEvent first =
        publish(seller, register(seller, product, 10, hoursFromNow(1), hoursFromNow(3)));
    SaleEvent overlapping = register(seller, product, 10, hoursFromNow(2), hoursFromNow(4));
    SaleEvent adjacent = register(seller, product, 10, hoursFromNow(3), hoursFromNow(4));

    assertThatThrownBy(() -> events.publish(seller, overlapping.getId(), key(), 0))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.PERIOD_OVERLAP);
    assertThat(events.publish(seller, adjacent.getId(), key(), 0).getStatus())
        .isEqualTo(SaleEventStatus.SCHEDULED);

    events.close(seller, first.getId(), key(), first.getVersion(), "일정 변경");
    events.close(seller, adjacent.getId(), key(), 1, "일정 변경");
    assertThat(events.publish(seller, overlapping.getId(), key(), 0).getStatus())
        .isEqualTo(SaleEventStatus.SCHEDULED);

    UUID stopped = stockedProduct(seller, 10);
    catalog.change(seller, stopped, key(), "키보드", 39_000, ProductStatus.STOPPED, 0);
    SaleEvent onStopped = register(seller, stopped, 1, hoursFromNow(1), hoursFromNow(2));
    assertThatThrownBy(() -> events.publish(seller, onStopped.getId(), key(), 0))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.PRODUCT_NOT_ON_SALE);
  }

  @Test
  @DisplayName("공개 후 종료 전에는 상품 변경·삭제를 거절하지만 재고 조정은 허용하고, 종료 뒤에는 상품을 바꿀 수 있다")
  void locksProductWhileEventIsInProgress() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent event =
        publish(seller, register(seller, product, 5, hoursFromNow(1), hoursFromNow(2)));

    assertThatThrownBy(
            () -> catalog.change(seller, product, key(), "키보드", 1, ProductStatus.ON_SALE, 0))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.PRODUCT_LOCKED_BY_EVENT);
    assertThatThrownBy(() -> catalog.delete(seller, product, key(), 0))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.PRODUCT_LOCKED_BY_EVENT);
    ProductInventory inventory = catalog.inventory(seller, product);
    catalog.adjustAvailable(seller, product, key(), 3, inventory.getVersion(), "입고");
    assertThat(catalog.inventory(seller, product).getAvailableQuantity()).isEqualTo(8);
    assertThat(catalog.sellerProduct(seller, product).getVersion()).isZero();

    events.close(seller, event.getId(), key(), 1, "판매 중단");

    assertThat(
            catalog
                .change(seller, product, key(), "키보드", 1, ProductStatus.ON_SALE, 0)
                .getUnitPrice())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("조기 종료는 남은 배정분을 사유와 함께 한 번만 돌리고 다시 종료하면 거절한다")
  void closesEarlyOnce() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent event =
        publish(seller, register(seller, product, 4, hoursFromNow(1), hoursFromNow(2)));

    SaleEvent closed = events.close(seller, event.getId(), key(), 1, "재고 오류");

    assertThat(closed.getStatus()).isEqualTo(SaleEventStatus.CLOSED);
    ProductInventory inventory = catalog.inventory(seller, product);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
    assertThat(inventory.getEventAllocatedQuantity()).isZero();
    assertThat(
            jdbc.queryForMap(
                "select available_delta, allocated_delta, reason from commerce.inventory_movements"
                    + " where operation = 'EVENT_RETURN' and business_ref = ?",
                event.getId()))
        .containsEntry("available_delta", 4L)
        .containsEntry("allocated_delta", -4L)
        .containsEntry("reason", "재고 오류");
    assertThatThrownBy(() -> events.close(seller, event.getId(), key(), 2, "재고 오류"))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.NOT_IN_PROGRESS);
    assertThat(count("inventory_movements where business_ref = ?", event.getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("시작 시각이 되면 열고, 종료 시각이 지난 이벤트만 한 번 닫아 남은 배정분을 돌린다")
  void opensAndClosesByPeriod() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent ending =
        publish(seller, register(seller, product, 3, hoursFromNow(1), hoursFromNow(2)));
    SaleEvent later =
        publish(seller, register(seller, product, 2, hoursFromNow(2), hoursFromNow(5)));
    Instant started = ending.getStartsAt();
    Instant ended = ending.getEndsAt();

    events.openStarted(started, 100);
    assertThat(events.sellerEvent(seller, ending.getId()).getStatus())
        .isEqualTo(SaleEventStatus.OPEN);
    assertThat(events.sellerEvent(seller, later.getId()).getStatus())
        .isEqualTo(SaleEventStatus.SCHEDULED);

    assertThat(events.endedEventIds(ended, 100))
        .contains(ending.getId())
        .doesNotContain(later.getId());
    assertThat(events.closeIfEnded(later.getId(), ended)).isFalse();
    assertThat(events.closeIfEnded(ending.getId(), ended)).isTrue();
    assertThat(events.closeIfEnded(ending.getId(), ended)).isFalse();

    assertThat(events.sellerEvent(seller, ending.getId()).getStatus())
        .isEqualTo(SaleEventStatus.CLOSED);
    ProductInventory inventory = catalog.inventory(seller, product);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
    assertThat(inventory.getEventAllocatedQuantity()).isEqualTo(2);
    assertThat(
            count(
                "inventory_movements where operation = 'EVENT_RETURN' and business_ref = ?",
                ending.getId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("같은 상품의 기간이 겹치는 두 이벤트를 동시에 공개하면 하나만 배정한다")
  void publishesOneOfConcurrentOverlappingEvents() throws Exception {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 100);
    List<SaleEvent> drafts =
        List.of(
            register(seller, product, 10, hoursFromNow(1), hoursFromNow(3)),
            register(seller, product, 20, hoursFromNow(2), hoursFromNow(4)));
    var next = new java.util.concurrent.atomic.AtomicInteger();

    List<Object> results =
        runConcurrently(
            2, () -> events.publish(seller, drafts.get(next.getAndIncrement()).getId(), key(), 0));

    assertThat(results.stream().filter(SaleEvent.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(SaleEventConflictException.class::isInstance)).hasSize(1);
    SaleEvent winner =
        (SaleEvent) results.stream().filter(SaleEvent.class::isInstance).findFirst().orElseThrow();
    ProductInventory inventory = catalog.inventory(seller, product);
    assertThat(inventory.getEventAllocatedQuantity()).isEqualTo(winner.getQuota());
    assertThat(inventory.getAvailableQuantity()).isEqualTo(100 - winner.getQuota());
  }

  @Test
  @DisplayName("미공개 이벤트는 공개 조회에서 숨기고, 타인 이벤트·상품은 찾을 수 없으며, 공개 이벤트는 게이지를 계산한다")
  void scopesVisibilityAndComputesGauge() {
    UUID seller = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    SaleEvent draft = register(seller, product, 5, hoursFromNow(1), hoursFromNow(2));

    assertThatThrownBy(() -> events.publishedEvent(draft.getId()))
        .isInstanceOf(SaleEventNotFoundException.class);
    assertThatThrownBy(() -> events.gauge(draft.getId()))
        .isInstanceOf(SaleEventNotFoundException.class);
    assertThatThrownBy(() -> events.sellerEvent(other, draft.getId()))
        .isInstanceOf(SaleEventNotFoundException.class);
    assertThatThrownBy(() -> events.publish(other, draft.getId(), key(), 0))
        .isInstanceOf(SaleEventNotFoundException.class);
    assertThatThrownBy(
            () ->
                events.register(
                    other, key(), product, "이벤트", 1_000, 1, hoursFromNow(1), hoursFromNow(2)))
        .isInstanceOf(ProductNotFoundException.class);

    publish(seller, draft);
    SaleEventService.GaugeView view = events.gauge(draft.getId());

    assertThat(view.gauge().admission()).isEqualTo(EventAdmission.NOT_STARTED);
    assertThat(view.gauge().remainingQuantity()).isEqualTo(5);
    assertThat(view.gauge().percent()).isZero();
    assertThat(events.publishedEvent(draft.getId()).getId()).isEqualTo(draft.getId());
  }

  @Test
  @DisplayName("같은 키로 다른 내용의 이벤트를 등록하면 충돌로 거절한다")
  void rejectsSameKeyWithDifferentRegistration() {
    UUID seller = UUID.randomUUID();
    UUID product = stockedProduct(seller, 10);
    String key = key();
    Instant starts = hoursFromNow(1);
    Instant ends = hoursFromNow(2);

    SaleEvent first = events.register(seller, key, product, "이벤트", 1_000, 1, starts, ends);
    SaleEvent replay = events.register(seller, key, product, "이벤트", 1_000, 1, starts, ends);

    assertThat(replay.getId()).isEqualTo(first.getId());
    assertThatThrownBy(() -> events.register(seller, key, product, "이벤트", 1_000, 2, starts, ends))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(count("sale_events where seller_id = ?", seller)).isEqualTo(1);
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

  private SaleEvent publish(UUID seller, SaleEvent draft) {
    return events.publish(seller, draft.getId(), key(), draft.getVersion());
  }

  private static Instant hoursFromNow(long hours) {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(hours));
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
