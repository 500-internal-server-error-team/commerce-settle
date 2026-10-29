package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 구매자 주소록의 주소 한 건. 배송 정보는 암호문으로만 보관한다. 주문은 생성 때 사본을 따로 저장하므로 여기의 변경·삭제가 과거 주문을 바꾸지 않는다. 기본 주소는 구매자당
 * 최대 한 개이며 삭제한 주소는 기본이 아니다.
 */
@Entity
@Table(name = "buyer_addresses")
public class BuyerAddress extends AuditableEntity {
  @Id private UUID id;
  private UUID buyerId;
  private byte[] recipientCiphertext;
  private byte[] phoneCiphertext;
  private byte[] addressCiphertext;

  @Column(name = "is_default")
  private boolean defaultAddress;

  private Instant deletedAt;
  @Version private Long version;

  protected BuyerAddress() {}

  private BuyerAddress(
      UUID id, UUID buyerId, SealedShippingAddress sealed, boolean defaultAddress, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.defaultAddress = defaultAddress;
    apply(sealed);
  }

  public static BuyerAddress register(
      UUID id, UUID buyerId, SealedShippingAddress sealed, boolean defaultAddress, Instant now) {
    return new BuyerAddress(id, buyerId, sealed, defaultAddress, now);
  }

  /** 기본 해제는 다른 주소를 기본으로 지정하는 쪽에서만 한다. 기본 주소를 false로 바꿔도 기본으로 남는다. */
  public void change(
      SealedShippingAddress sealed, boolean makeDefault, long expectedVersion, Instant now) {
    requireVersion(expectedVersion);
    apply(sealed);
    if (makeDefault) {
      this.defaultAddress = true;
    }
    touch(now);
  }

  public void clearDefault(Instant now) {
    this.defaultAddress = false;
    touch(now);
  }

  /** 기본 주소도 삭제할 수 있으며 남은 주소에 기본을 넘기지 않는다. */
  public void delete(long expectedVersion, Instant now) {
    requireVersion(expectedVersion);
    this.defaultAddress = false;
    this.deletedAt = truncate(now);
    touch(now);
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public boolean isOwnedBy(UUID buyerId) {
    return this.buyerId.equals(buyerId);
  }

  private void apply(SealedShippingAddress sealed) {
    Objects.requireNonNull(sealed, "sealed");
    this.recipientCiphertext = sealed.recipient();
    this.phoneCiphertext = sealed.phone();
    this.addressCiphertext = sealed.address();
  }

  private void requireVersion(long expectedVersion) {
    if (expectedVersion != getVersion()) {
      throw new VersionConflictException("buyer address", expectedVersion, getVersion());
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public SealedShippingAddress getSealed() {
    return new SealedShippingAddress(recipientCiphertext, phoneCiphertext, addressCiphertext);
  }

  public boolean isDefault() {
    return defaultAddress;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
