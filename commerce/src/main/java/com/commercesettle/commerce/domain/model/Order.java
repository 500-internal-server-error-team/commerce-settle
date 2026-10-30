package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 일반 주문의 최초 사본. 예약·결제 실행과 이후 상태 전이는 후속 유즈케이스가 맡는다. */
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
  private Instant paymentWaitUntil;

  @Column(length = 32)
  private String policyVersion;

  @Version private Long version;

  protected Order() {}

  private Order(
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
    super(now);
    if (quantity < 1 || quantity > 10) {
      throw new IllegalArgumentException("Regular order quantity must be 1..10");
    }
    Objects.requireNonNull(product, "product");
    this.amounts = new OrderAmounts(product.getUnitPrice() * quantity, 0, 0, mileageAmount);
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
    this.id = UUID.randomUUID();
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.sellerId = product.getSellerId();
    this.productId = product.getId();
    this.productName = product.getName();
    this.unitPrice = product.getUnitPrice();
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
    return new Order(
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

  public UUID getId() {
    return id;
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
