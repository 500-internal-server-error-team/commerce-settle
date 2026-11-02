package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.OrderQuoteView;
import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.dto.PaymentContextView;
import com.commercesettle.commerce.application.dto.SellerOrderView;
import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderLifecycleEvents;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.OrderRequestException;
import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.AuditLog;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAmounts;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderWorkflowService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final OrderRepository orders;
  private final AccountRepository accounts;
  private final ProductRepository products;
  private final AddressCipher cipher;
  private final InventoryReservationService inventory;
  private final OrderBenefits benefits;
  private final IdempotentRequests keys;
  private final OrderEventOutbox outbox;
  private final AuditLog audit;
  private final Clock clock;
  private final OrderAdmissionService admission;

  public OrderWorkflowService(
      OrderRepository orders,
      AccountRepository accounts,
      ProductRepository products,
      AddressCipher cipher,
      InventoryReservationService inventory,
      OrderBenefits benefits,
      IdempotentRequests keys,
      OrderEventOutbox outbox,
      AuditLog audit,
      Clock clock,
      OrderAdmissionService admission) {
    this.orders = orders;
    this.accounts = accounts;
    this.products = products;
    this.cipher = cipher;
    this.inventory = inventory;
    this.benefits = benefits;
    this.keys = keys;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
    this.admission = admission;
  }

  @Transactional(readOnly = true)
  public OrderQuoteView quote(
      UUID buyer,
      UUID productId,
      int quantity,
      UUID eventId,
      UUID couponId,
      long mileage,
      Order.ExternalMethod method) {
    requireAccount(ActorKind.BUYER, buyer);
    if (quantity < 1
        || quantity > 10
        || mileage < 0
        || couponId != null && mileage != 0
        || eventId != null && quantity != 1) throw invalid("quantity");
    var product =
        products
            .findById(productId)
            .filter(p -> p.isPubliclyVisible())
            .orElseThrow(OrderWorkflowService::notFound);
    var now = clock.instant();
    var offer =
        benefits.quote(
            buyer,
            productId,
            product.getSellerId(),
            product.getUnitPrice(),
            quantity,
            couponId,
            eventId,
            method,
            now);
    OrderAmounts a;
    try {
      a =
          new OrderAmounts(
              Math.multiplyExact(offer.unitPrice(), quantity),
              offer.sellerCouponAmount(),
              offer.platformCouponAmount(),
              mileage);
    } catch (IllegalArgumentException e) {
      throw invalid("mileageAmount");
    }
    if ((a.externalAmount() > 0) != (method != null)) throw invalid("externalMethod");
    return new OrderQuoteView(
        a.grossAmount(),
        a.couponAmount(),
        a.totalAmount(),
        a.mileageAmount(),
        a.externalAmount(),
        product.getVersion(),
        offer.eventVersion(),
        now,
        a.sellerCouponAmount(),
        a.platformCouponAmount(),
        a.settlementBaseAmount());
  }

  @Transactional(readOnly = true)
  public PageResult<SellerOrderView> sellerOrders(UUID seller, int page, int size) {
    requireAccount(ActorKind.SELLER, seller);
    if (page < 0 || size < 1 || size > 100) throw invalid("page");
    var found = orders.findBySeller(seller, page, size);
    // 목록에는 주소를 복호화하지 않는다. 이행 대상 단건에서만 사본을 제공한다.
    return new PageResult<>(
        found.items().stream().map(o -> new SellerOrderView(OrderView.from(o), null)).toList(),
        page,
        size,
        found.totalElements());
  }

  @Transactional(readOnly = true)
  public SellerOrderView sellerOrder(UUID seller, UUID id) {
    requireAccount(ActorKind.SELLER, seller);
    var order =
        orders
            .findById(id)
            .filter(o -> o.getSellerId().equals(seller))
            .orElseThrow(OrderWorkflowService::notFound);
    var address =
        order.getStatus() == Order.Status.PAID || order.getStatus() == Order.Status.REFUND_PENDING
            ? orders
                .findAddressByBuyerAndOrderId(order.getBuyerId(), id)
                .map(a -> cipher.open(a.getId(), a.getSealed()))
                .orElse(null)
            : null;
    return new SellerOrderView(OrderView.from(order), address);
  }

  @Transactional(readOnly = true)
  public PaymentContextView paymentContext(UUID id) {
    var o = orders.findById(id).orElseThrow(OrderWorkflowService::notFound);
    var a = o.getAmounts();
    return new PaymentContextView(
        a.grossAmount(),
        a.couponAmount(),
        a.totalAmount(),
        a.mileageAmount(),
        a.externalAmount(),
        id,
        o.getBuyerId(),
        o.getSellerId(),
        o.getVersion(),
        o.getPaymentCycleId(),
        o.getProductId(),
        o.getStatus().name(),
        o.getPolicyVersion(),
        o.getPaymentWaitUntil(),
        a.sellerCouponAmount(),
        a.platformCouponAmount(),
        a.settlementBaseAmount());
  }

  @Transactional
  public OrderView retry(UUID buyer, UUID id, String key, long expected) {
    requireAccount(ActorKind.BUYER, buyer);
    return keys.executePermanent(
        request("BUYER", buyer, "RETRY_PAYMENT", key, List.of(id, expected)),
        id,
        202,
        ignored -> {
          var o = ownedLocked(ActorKind.BUYER, buyer, id);
          o.requireVersion(expected);
          var before = o.getStatus();
          o.requirePaymentRetry(clock.instant());
          admission.requireAdmission();
          o.retryPayment(UUID.randomUUID(), clock.instant());
          if (o.getEventId() == null)
            inventory.reserveAgain(id, o.getPaymentCycleId(), o.getPaymentWaitUntil());
          benefits.retry(o, clock.instant());
          orders.save(o);
          changed(o, before, "BUYER", buyer.toString(), "PAYMENT_RETRY", null);
          paymentRequested(o);
          return OrderView.from(o);
        },
        replay -> OrderView.from(ownedLocked(ActorKind.BUYER, buyer, replay)));
  }

  @Transactional
  public OrderView cancel(UUID buyer, UUID id, String key, long expected, String reason) {
    requireAccount(ActorKind.BUYER, buyer);
    return keys.executePermanent(
        request("BUYER", buyer, "CANCEL_ORDER", key, List.of(id, expected, reason)),
        id,
        202,
        requestId -> {
          var o = ownedLocked(ActorKind.BUYER, buyer, id);
          o.requireVersion(expected);
          cancellation(o, requestId, "BUYER", buyer.toString());
          return OrderView.from(o);
        },
        replay -> OrderView.from(ownedLocked(ActorKind.BUYER, buyer, replay)));
  }

  @Transactional
  public OrderView ship(UUID seller, UUID id, String key, long expected, String reference) {
    requireAccount(ActorKind.SELLER, seller);
    return keys.executePermanent(
        request("SELLER", seller, "SHIP_ORDER", key, List.of(id, expected, reference)),
        id,
        200,
        ignored -> {
          var o = ownedLocked(ActorKind.SELLER, seller, id);
          o.requireVersion(expected);
          var before = o.getStatus();
          o.ship(clock.instant());
          orders.save(o);
          changed(o, before, "SELLER", seller.toString(), "SHIPMENT_STARTED", null);
          return OrderView.from(o);
        },
        replay -> OrderView.from(ownedLocked(ActorKind.SELLER, seller, replay)));
  }

  @Transactional(readOnly = true)
  public List<UUID> due(int limit, UUID after) {
    return orders.findDue(clock.instant(), limit, after);
  }

  @Transactional
  public void advance(UUID id) {
    var o = orders.findLockedById(id).orElseThrow(OrderWorkflowService::notFound);
    var now = clock.instant();
    var before = o.getStatus();
    if ((before == Order.Status.PAYMENT_PENDING || before == Order.Status.DECLINED)
        && !now.isBefore(o.getPaymentWaitUntil())) {
      cancellation(o, UUID.randomUUID(), "SYSTEM", "order-clock");
      return;
    }
    boolean delivered = o.deliverIfDue(now);
    boolean confirmed = o.confirmPurchaseIfDue(now);
    if (!delivered && !confirmed) return;
    orders.save(o);
    changed(
        o,
        before,
        "SYSTEM",
        "order-clock",
        confirmed ? "PURCHASE_CONFIRMED" : "DELIVERY_COMPLETED",
        null);
    if (confirmed)
      emit(
          o,
          "PurchaseConfirmed",
          o.getPaymentId(),
          new OrderLifecycleEvents.PurchaseConfirmed(
              o.getPaymentId(),
              id,
              o.getVersion(),
              time(now),
              time(o.getDeliveredAt()),
              time(o.getRefundDeadlineAt())));
  }

  void cancellation(Order o, UUID requestId, String actor, String ref) {
    var before = o.getStatus();
    if (!o.requestCancellation(clock.instant())) return;
    orders.save(o);
    changed(o, before, actor, ref, "CANCELLATION_REQUESTED", null);
    emit(
        o,
        "PaymentCancellationRequested",
        o.getId(),
        new OrderLifecycleEvents.CancellationRequested(
            o.getId(), o.getVersion(), o.getPaymentCycleId(), requestId));
  }

  void paymentRequested(Order o) {
    var a = o.getAmounts();
    var payload =
        new OrderPaymentRequestedPayload(
            o.getId(),
            o.getBuyerId(),
            o.getSellerId(),
            o.getPaymentCycleId(),
            o.getProductId(),
            o.getVersion(),
            OrderPaymentRequestedPayload.Status.PAYMENT_PENDING,
            o.getPolicyVersion(),
            time(o.getPaymentWaitUntil()),
            o.getExternalMethod() == null
                ? null
                : OrderPaymentRequestedPayload.ExternalMethod.valueOf(o.getExternalMethod().name()),
            o.getMethodAccountRef(),
            a.grossAmount(),
            a.couponAmount(),
            a.totalAmount(),
            a.mileageAmount(),
            a.externalAmount(),
            a.sellerCouponAmount(),
            a.platformCouponAmount(),
            a.settlementBaseAmount());
    outbox.appendPaymentRequested(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "OrderPaymentRequested",
            1,
            time(clock.instant()),
            o.getId(),
            o.getVersion(),
            payload));
  }

  void emit(Order order, String type, UUID key, Object payload) {
    outbox.append(
        new EventEnvelope<>(
            UUID.randomUUID(),
            type,
            1,
            time(clock.instant()),
            order.getId(),
            order.getVersion(),
            payload),
        key);
  }

  void changed(
      Order o, Order.Status before, String actor, String ref, String reason, UUID eventId) {
    audit.record(
        new AuditLog.Entry(
            actor,
            ref,
            AuditLog.Action.ORDER_TRANSITION,
            "ORDER",
            o.getId(),
            before.name(),
            o.getStatus().name(),
            reason,
            eventId,
            o.getId().toString(),
            clock.instant()));
  }

  Order ownedLocked(ActorKind kind, UUID actor, UUID id) {
    return orders
        .findLockedById(id)
        .filter(o -> (kind == ActorKind.BUYER ? o.getBuyerId() : o.getSellerId()).equals(actor))
        .orElseThrow(OrderWorkflowService::notFound);
  }

  void requireAccount(ActorKind kind, UUID id) {
    accounts
        .findById(kind, id)
        .filter(Account::canLogIn)
        .orElseThrow(AuthenticationFailedException::new);
  }

  static IdempotentRequests.Request request(
      String kind, UUID actor, String operation, String key, List<?> body) {
    return new IdempotentRequests.Request(kind, actor.toString(), operation, key, body);
  }

  static OffsetDateTime time(Instant instant) {
    return instant.atZone(SEOUL).toOffsetDateTime();
  }

  static OrderRequestException notFound() {
    return new OrderRequestException(OrderRequestException.Kind.NOT_FOUND, "orderId");
  }

  static OrderRequestException invalid(String field) {
    return new OrderRequestException(OrderRequestException.Kind.INVALID_REQUEST, field);
  }
}
