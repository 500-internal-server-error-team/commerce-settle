package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 주문 당시 가격·결제 구성·배송 정책과 업무 상태. 예약·이벤트 저장은 응용 서비스가 조율한다. */
@Entity(name = "CommerceOrder")
@Table(name = "orders")
public class Order extends AuditableEntity {
  @Id private UUID id;
  private UUID buyerId;
  private UUID sellerId;
  private UUID productId;
  private UUID eventId;
  private UUID buyerCouponId;
  private UUID paymentId;

  @Column(length = 120)
  private String productName;

  private long unitPrice;
  private int quantity;
  @Embedded private OrderAmounts amounts;
  private long couponAmount;
  private long settlementBaseAmount;
  private long totalAmount;
  private long externalAmount;
  private UUID paymentCycleId;

  @Enumerated(EnumType.STRING)
  @Column(length = 24)
  private ExternalMethod externalMethod;

  @Column(length = 16)
  private String providerId;

  @Column(length = 100)
  private String methodAccountRef;

  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private Status status;

  @Enumerated(EnumType.STRING)
  @Column(length = 24)
  private FulfillmentStatus fulfillmentStatus;

  @Embedded private OrderDelivery delivery;
  private Instant shippedAt;
  private Instant deliveryDueAt;
  private Instant deliveredAt;
  private Instant paymentConfirmedAt;
  private Instant refundDeadlineAt;
  private Instant purchaseConfirmedAt;
  private Instant closedAt;
  private Instant paymentWaitUntil;

  @Column(length = 32)
  private String policyVersion;

  @Version private Long version;

  protected Order() {}

  private Order(
      UUID id,
      UUID buyerId,
      Product product,
      int quantity,
      ExternalMethod externalMethod,
      String methodAccountRef,
      long seed,
      String calendarVersion,
      List<LocalDate> holidays,
      String policyVersion,
      Instant now,
      long capturedUnitPrice,
      OrderAmounts capturedAmounts,
      UUID capturedEventId,
      UUID couponId) {
    super(now);
    if (quantity < 1 || quantity > 10) {
      throw new IllegalArgumentException("Regular order quantity must be 1..10");
    }
    Objects.requireNonNull(product, "product");
    this.amounts = capturedAmounts;
    if (amounts.externalAmount() > 0) {
      Objects.requireNonNull(externalMethod, "externalMethod");
      if (methodAccountRef == null
          || methodAccountRef.isBlank()
          || methodAccountRef.length() > 100) {
        throw new IllegalArgumentException("External payment requires a test account reference");
      }
    } else if (externalMethod != null || methodAccountRef != null) {
      throw new IllegalArgumentException(
          "Full mileage payment must not specify an external method");
    }
    if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 32) {
      throw new IllegalArgumentException("Invalid order policy version");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.sellerId = product.getSellerId();
    this.productId = product.getId();
    this.productName = product.getName();
    this.unitPrice = capturedUnitPrice;
    this.eventId = capturedEventId;
    this.buyerCouponId = couponId;
    this.quantity = quantity;
    this.couponAmount = amounts.couponAmount();
    this.settlementBaseAmount = amounts.settlementBaseAmount();
    this.totalAmount = amounts.totalAmount();
    this.externalAmount = amounts.externalAmount();
    this.paymentCycleId = UUID.randomUUID();
    this.externalMethod = externalMethod;
    this.methodAccountRef = methodAccountRef;
    this.status = Status.PAYMENT_PENDING;
    this.fulfillmentStatus = FulfillmentStatus.UNSHIPPED;
    this.delivery = OrderDelivery.capture(id, seed, calendarVersion, holidays);
    this.paymentWaitUntil = getCreatedAt().plusSeconds(600);
    this.policyVersion = policyVersion;
  }

  /** 상품·구매자·주소의 현재 유효성은 호출할 주문 응용 서비스가 같은 트랜잭션에서 검사한다. */
  public static Order pendingRegular(
      UUID buyerId,
      Product product,
      int quantity,
      long mileageAmount,
      ExternalMethod externalMethod,
      String methodAccountRef,
      long seed,
      String calendarVersion,
      List<LocalDate> holidays,
      String policyVersion,
      Instant now) {
    return pendingRegular(
        UUID.randomUUID(),
        buyerId,
        product,
        quantity,
        mileageAmount,
        externalMethod,
        methodAccountRef,
        seed,
        calendarVersion,
        holidays,
        policyVersion,
        now);
  }

  /** 멱등 기록에 먼저 연결한 주문 ID를 사용한다. */
  public static Order pendingRegular(
      UUID id,
      UUID buyerId,
      Product product,
      int quantity,
      long mileageAmount,
      ExternalMethod externalMethod,
      String methodAccountRef,
      long seed,
      String calendarVersion,
      List<LocalDate> holidays,
      String policyVersion,
      Instant now) {
    return new Order(
        id,
        buyerId,
        product,
        quantity,
        externalMethod,
        methodAccountRef,
        seed,
        calendarVersion,
        holidays,
        policyVersion,
        now,
        product.getUnitPrice(),
        new OrderAmounts(product.getUnitPrice() * quantity, 0, 0, mileageAmount),
        null,
        null);
  }

  public UUID getId() {
    return id;
  }

  public static Order pendingOffer(
      UUID id,
      UUID buyerId,
      Product product,
      int quantity,
      UUID eventId,
      UUID couponId,
      long unitPrice,
      OrderAmounts amounts,
      ExternalMethod method,
      String account,
      long seed,
      Instant now) {
    if (eventId != null && quantity != 1
        || unitPrice < 1
        || amounts.grossAmount() != Math.multiplyExact(unitPrice, quantity)
        || couponId == null && amounts.couponAmount() != 0
        || couponId != null && amounts.mileageAmount() != 0) {
      throw new IllegalArgumentException("Invalid order offer");
    }
    return new Order(
        id,
        buyerId,
        product,
        quantity,
        method,
        account,
        seed,
        "weekdays-v1",
        List.of(),
        "order-regular-v1",
        now,
        unitPrice,
        amounts,
        eventId,
        couponId);
  }

  public UUID getBuyerCouponId() {
    return buyerCouponId;
  }

  public void requireVersion(long expected) {
    if (expected != getVersion())
      throw new VersionConflictException("order", expected, getVersion());
  }

  /** 이전 회차는 무변경으로 끝내고, 같은 회차의 모순은 검토 대상으로 보낸다. */
  public boolean applyPayment(
      UUID id, UUID cycle, Status result, Instant confirmedAt, Instant now) {
    Objects.requireNonNull(id, "paymentId");
    if (!paymentCycleId.equals(Objects.requireNonNull(cycle))) return false;
    if (paymentId != null && !paymentId.equals(id)) throw conflict();
    if (result != Status.PAID && result != Status.DECLINED && result != Status.CANCELLED)
      throw conflict();
    if (status == Status.CANCEL_PENDING && result == Status.DECLINED) return false;
    if (status == result || result == Status.PAID && paymentConfirmedAt != null) return false;
    boolean pending = status == Status.PAYMENT_PENDING || status == Status.CANCEL_PENDING;
    if (!pending && !(status == Status.DECLINED && result == Status.CANCELLED)) throw conflict();
    if (result == Status.CANCELLED && status != Status.CANCEL_PENDING) throw conflict();
    if (result == Status.PAID) {
      Objects.requireNonNull(confirmedAt);
      if (confirmedAt.isBefore(getCreatedAt()) || confirmedAt.isAfter(now)) throw conflict();
      paymentConfirmedAt = truncate(confirmedAt);
    }
    paymentId = Objects.requireNonNull(id);
    status = result;
    if (result == Status.CANCELLED) closedAt = truncate(now);
    touch(now);
    return true;
  }

  public void retryPayment(UUID cycle, Instant now) {
    requirePaymentRetry(now);
    paymentCycleId = Objects.requireNonNull(cycle);
    status = Status.PAYMENT_PENDING;
    touch(now);
  }

  public void requirePaymentRetry(Instant now) {
    if (status != Status.DECLINED || !now.isBefore(paymentWaitUntil)) throw conflict();
  }

  public boolean requestCancellation(Instant now) {
    if (status == Status.CANCEL_PENDING) return false;
    if (status != Status.PAYMENT_PENDING && status != Status.DECLINED) throw conflict();
    status = Status.CANCEL_PENDING;
    touch(now);
    return true;
  }

  public void ship(Instant now) {
    if (status != Status.PAID || fulfillmentStatus != FulfillmentStatus.UNSHIPPED) throw conflict();
    shippedAt = truncate(now);
    var due = shippedAt.atZone(ZoneId.of("Asia/Seoul"));
    int remaining = delivery.getDeliveryBusinessDays();
    while (remaining > 0) {
      due = due.plusDays(1);
      if (due.getDayOfWeek() != DayOfWeek.SATURDAY
          && due.getDayOfWeek() != DayOfWeek.SUNDAY
          && !delivery.getDeliveryCalendar().holidays().contains(due.toLocalDate())) remaining--;
    }
    deliveryDueAt = due.toInstant();
    fulfillmentStatus = FulfillmentStatus.SHIPPED;
    touch(now);
  }

  public boolean deliverIfDue(Instant now) {
    if (fulfillmentStatus != FulfillmentStatus.SHIPPED
        || now.isBefore(deliveryDueAt)
        || status != Status.PAID && status != Status.REFUND_PENDING) return false;
    deliveredAt = deliveryDueAt;
    refundDeadlineAt = deliveredAt.plusSeconds(168 * 3600);
    fulfillmentStatus = FulfillmentStatus.DELIVERED;
    touch(now);
    return true;
  }

  public void beginRefund(boolean cancellation, Instant now) {
    if (status == Status.REFUND_PENDING)
      throw new OrderStateException(OrderStateException.Reason.REFUND_IN_PROGRESS);
    if (status != Status.PAID) throw conflict();
    deliverIfDue(now);
    if (refundDeadlineAt != null && now.isAfter(refundDeadlineAt))
      throw new OrderStateException(OrderStateException.Reason.REFUND_WINDOW_CLOSED);
    if (cancellation != (fulfillmentStatus == FulfillmentStatus.UNSHIPPED)) throw conflict();
    status = Status.REFUND_PENDING;
    touch(now);
  }

  public void resumeAfterRefund(Instant now) {
    if (status != Status.REFUND_PENDING) throw conflict();
    status = Status.PAID;
    touch(now);
  }

  public void receiveReturn(Instant now) {
    if (status != Status.REFUND_PENDING && status != Status.REFUNDED
        || fulfillmentStatus != FulfillmentStatus.SHIPPED
            && fulfillmentStatus != FulfillmentStatus.DELIVERED) throw conflict();
    fulfillmentStatus = FulfillmentStatus.RETURN_RECEIVED;
    touch(now);
  }

  public void restoreRefund(Instant now) {
    if (status != Status.REFUND_PENDING) throw conflict();
    status = Status.REFUNDED;
    closedAt = truncate(now);
    touch(now);
  }

  public boolean confirmPurchaseIfDue(Instant now) {
    if (status != Status.PAID
        || fulfillmentStatus != FulfillmentStatus.DELIVERED
        || !now.isAfter(refundDeadlineAt)) return false;
    status = Status.PURCHASE_CONFIRMED;
    purchaseConfirmedAt = truncate(now);
    closedAt = truncate(now);
    touch(now);
    return true;
  }

  private static OrderStateException conflict() {
    return new OrderStateException(OrderStateException.Reason.STATE_CONFLICT);
  }

  /** 접수된 결제 ID만 연결한다. 결제 상태·금액·예약을 전이시키지 않는다. */
  public PaymentLinkResult linkPayment(
      UUID acceptedPaymentId, UUID cycleId, long requestedVersion, Instant now) {
    Objects.requireNonNull(acceptedPaymentId, "acceptedPaymentId");
    Objects.requireNonNull(cycleId, "cycleId");
    if (requestedVersion < 0) throw new IllegalArgumentException("Invalid order version");
    if (!paymentCycleId.equals(cycleId)) return PaymentLinkResult.STALE_CYCLE;
    if (paymentId != null) {
      if (!paymentId.equals(acceptedPaymentId)) return PaymentLinkResult.PAYMENT_ID_CONFLICT;
      return requestedVersion <= getVersion()
          ? PaymentLinkResult.ALREADY_LINKED
          : PaymentLinkResult.VERSION_CONFLICT;
    }
    if (requestedVersion != getVersion()) return PaymentLinkResult.VERSION_CONFLICT;
    if (status != Status.PAYMENT_PENDING) return PaymentLinkResult.STATE_CONFLICT;
    paymentId = acceptedPaymentId;
    touch(now);
    return PaymentLinkResult.LINKED;
  }

  public enum PaymentLinkResult {
    LINKED,
    ALREADY_LINKED,
    STALE_CYCLE,
    VERSION_CONFLICT,
    PAYMENT_ID_CONFLICT,
    STATE_CONFLICT
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public UUID getProductId() {
    return productId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public Instant getPaymentConfirmedAt() {
    return paymentConfirmedAt;
  }

  public Instant getShippedAt() {
    return shippedAt;
  }

  public Instant getDeliveryDueAt() {
    return deliveryDueAt;
  }

  public Instant getDeliveredAt() {
    return deliveredAt;
  }

  public Instant getRefundDeadlineAt() {
    return refundDeadlineAt;
  }

  public Instant getPurchaseConfirmedAt() {
    return purchaseConfirmedAt;
  }

  public String getProductName() {
    return productName;
  }

  public long getUnitPrice() {
    return unitPrice;
  }

  public int getQuantity() {
    return quantity;
  }

  public OrderAmounts getAmounts() {
    return amounts;
  }

  public UUID getPaymentCycleId() {
    return paymentCycleId;
  }

  public ExternalMethod getExternalMethod() {
    return externalMethod;
  }

  public String getMethodAccountRef() {
    return methodAccountRef;
  }

  public Status getStatus() {
    return status;
  }

  public FulfillmentStatus getFulfillmentStatus() {
    return fulfillmentStatus;
  }

  public OrderDelivery getDelivery() {
    return delivery;
  }

  public Instant getPaymentWaitUntil() {
    return paymentWaitUntil;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }

  public enum ExternalMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    EASY_PAY
  }

  public enum Status {
    PAYMENT_PENDING,
    PAID,
    DECLINED,
    CANCEL_PENDING,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED,
    PURCHASE_CONFIRMED
  }

  public enum FulfillmentStatus {
    UNSHIPPED,
    SHIPPED,
    DELIVERED,
    RETURN_RECEIVED
  }
}
