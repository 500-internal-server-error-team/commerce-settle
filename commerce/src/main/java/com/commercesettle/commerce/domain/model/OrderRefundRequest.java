package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_refund_requests")
public class OrderRefundRequest extends AuditableEntity {
  @Id private UUID id;
  private UUID orderId;
  private UUID buyerId;
  private UUID sellerId;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private Kind kind;

  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private Status status;

  private Instant acceptedAt;

  @Column(length = 200)
  private String reason;

  private UUID refundId;
  private Instant decisionAt;
  private Instant receivedAt;

  @Column(length = 200)
  private String closedReason;

  @Column(length = 200)
  private String closureEvidenceRef;

  @Version private Long version;

  protected OrderRefundRequest() {}

  private OrderRefundRequest(Instant now) {
    super(now);
  }

  public static OrderRefundRequest create(
      UUID id, Order order, Kind kind, String reason, Instant now) {
    var r = new OrderRefundRequest(now);
    r.id = Objects.requireNonNull(id);
    r.orderId = order.getId();
    r.buyerId = order.getBuyerId();
    r.sellerId = order.getSellerId();
    r.kind = Objects.requireNonNull(kind);
    r.reason = text(reason);
    r.acceptedAt = truncate(now);
    r.status = kind == Kind.CANCEL ? Status.APPROVED : Status.PENDING_SELLER;
    r.decisionAt = kind == Kind.CANCEL ? truncate(now) : null;
    return r;
  }

  public void requireVersion(long expected) {
    if (expected != getVersion())
      throw new VersionConflictException("refundRequest", expected, getVersion());
  }

  public void withdraw(Instant now) {
    require(Status.PENDING_SELLER);
    status = Status.WITHDRAWN;
    touch(now);
  }

  public void decide(boolean approve, Instant now) {
    require(Status.PENDING_SELLER);
    status = approve ? Status.APPROVED : Status.REJECTED;
    decisionAt = truncate(now);
    touch(now);
  }

  public boolean linkRefund(UUID id, Instant now) {
    Objects.requireNonNull(id);
    if (refundId != null) {
      if (!refundId.equals(id)) throw conflict();
      return false;
    }
    require(Status.APPROVED);
    refundId = id;
    status = Status.REFUNDING;
    touch(now);
    return true;
  }

  public boolean receive(Instant now) {
    if (receivedAt != null) return false;
    if (kind != Kind.RETURN
        || status != Status.APPROVED
            && status != Status.REFUNDING
            && status != Status.COMPLETED
            && status != Status.FAILED_REVIEW) throw conflict();
    receivedAt = truncate(now);
    touch(now);
    return true;
  }

  public boolean complete(Instant now) {
    if (status == Status.COMPLETED) return false;
    require(Status.REFUNDING);
    status = Status.COMPLETED;
    touch(now);
    return true;
  }

  public boolean fail(Instant now) {
    if (status == Status.FAILED_REVIEW) return false;
    require(Status.REFUNDING);
    status = Status.FAILED_REVIEW;
    touch(now);
    return true;
  }

  public void close(String reason, String evidence, Instant now) {
    require(Status.FAILED_REVIEW);
    if (receivedAt != null) throw conflict();
    closedReason = text(reason);
    closureEvidenceRef = text(evidence);
    status = Status.CLOSED;
    touch(now);
  }

  private static String text(String value) {
    if (value == null || value.isBlank() || value.length() > 200)
      throw new IllegalArgumentException("Invalid refund text");
    return value;
  }

  private void require(Status expected) {
    if (status != expected) throw conflict();
  }

  private static OrderStateException conflict() {
    return new OrderStateException(OrderStateException.Reason.STATE_CONFLICT);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public UUID getRefundId() {
    return refundId;
  }

  public Kind getKind() {
    return kind;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getAcceptedAt() {
    return acceptedAt;
  }

  public Instant getDecisionAt() {
    return decisionAt;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }

  public enum Kind {
    CANCEL,
    RETURN
  }

  public enum Status {
    PENDING_SELLER,
    APPROVED,
    REFUNDING,
    COMPLETED,
    REJECTED,
    WITHDRAWN,
    FAILED_REVIEW,
    CLOSED
  }
}
