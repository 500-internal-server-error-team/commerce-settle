package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 판매자가 등록한 상품. 가격·상태 변경은 기대 버전을 확인한 뒤에만 반영한다. */
@Entity
@Table(name = "products")
public class Product extends AuditableEntity {
  public static final int MAX_NAME_LENGTH = 120;
  public static final long MIN_UNIT_PRICE = 1;
  public static final long MAX_UNIT_PRICE = 10_000_000;

  @Id private UUID id;
  private UUID sellerId;
  private String name;
  private long unitPrice;

  @Enumerated(EnumType.STRING)
  private ProductStatus status;

  private Instant deletedAt;
  @Version private Long version;

  protected Product() {}

  private Product(
      UUID id, UUID sellerId, String name, long unitPrice, ProductStatus status, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    apply(name, unitPrice, status);
  }

  public static Product register(
      UUID id, UUID sellerId, String name, long unitPrice, ProductStatus status, Instant now) {
    return new Product(id, sellerId, name, unitPrice, status, now);
  }

  public void change(
      String name, long unitPrice, ProductStatus status, long expectedVersion, Instant now) {
    requireVersion(expectedVersion);
    apply(name, unitPrice, status);
    touch(now);
  }

  /** 새 판매만 막는다. 주문 사본·발송·환불 이력은 상품 ID로 계속 참조한다. */
  public void delete(long expectedVersion, Instant now) {
    requireVersion(expectedVersion);
    this.status = ProductStatus.DELETED;
    this.deletedAt = truncate(now);
    touch(now);
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public boolean isOwnedBy(UUID sellerId) {
    return this.sellerId.equals(sellerId);
  }

  public boolean isPubliclyVisible() {
    return !isDeleted() && status == ProductStatus.ON_SALE;
  }

  private void apply(String name, long unitPrice, ProductStatus status) {
    Objects.requireNonNull(name, "name");
    if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("name length must be 1.." + MAX_NAME_LENGTH);
    }
    if (unitPrice < MIN_UNIT_PRICE || unitPrice > MAX_UNIT_PRICE) {
      throw new IllegalArgumentException("unitPrice out of range");
    }
    if (Objects.requireNonNull(status, "status") == ProductStatus.DELETED) {
      throw new IllegalArgumentException("DELETED is reachable only by delete");
    }
    this.name = name;
    this.unitPrice = unitPrice;
    this.status = status;
  }

  private void requireVersion(long expectedVersion) {
    if (expectedVersion != getVersion()) {
      throw new VersionConflictException("product", expectedVersion, getVersion());
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public String getName() {
    return name;
  }

  public long getUnitPrice() {
    return unitPrice;
  }

  public ProductStatus getStatus() {
    return status;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
