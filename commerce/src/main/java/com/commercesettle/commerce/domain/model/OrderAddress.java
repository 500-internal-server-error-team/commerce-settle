package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 주소록과 독립된 주문 당시 배송지 암호문. 이 구현에서는 사본 변경·마스킹을 제공하지 않는다. */
@Entity
@Table(name = "order_addresses")
public class OrderAddress extends AuditableEntity {
  @Id private UUID id;
  private UUID orderId;
  private byte[] recipientCiphertext;
  private byte[] phoneCiphertext;
  private byte[] addressCiphertext;
  private Instant maskedAt;

  @Column(length = 200)
  private String holdReason;

  @Version private Long version;

  protected OrderAddress() {}

  private OrderAddress(UUID id, UUID orderId, SealedShippingAddress sealed, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(sealed, "sealed");
    this.recipientCiphertext = ciphertext(sealed.recipient());
    this.phoneCiphertext = ciphertext(sealed.phone());
    this.addressCiphertext = ciphertext(sealed.address());
  }

  /** 호출자는 새 주문 주소 ID에 묶어 다시 암호화한 배송 정보를 전달한다. */
  public static OrderAddress snapshot(
      UUID id, UUID orderId, SealedShippingAddress sealed, Instant now) {
    return new OrderAddress(id, orderId, sealed, now);
  }

  private static byte[] ciphertext(byte[] value) {
    if (value.length == 0) {
      throw new IllegalArgumentException("Order address ciphertext must not be empty");
    }
    return value.clone();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public SealedShippingAddress getSealed() {
    return new SealedShippingAddress(
        recipientCiphertext.clone(), phoneCiphertext.clone(), addressCiphertext.clone());
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
