package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.CouponCampaignConflictException;
import com.commercesettle.commerce.domain.exception.CouponCampaignConflictException.Reason;
import com.commercesettle.commerce.domain.exception.InvalidCouponTermsException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 발급 당시 혜택 조건과 할인 비용 집계. 혜택 조건은 발급 뒤 바꾸지 않는다. 발급 수·약속액·사용액은 발급·사용·복구와 같은 트랜잭션에서 저장소의 원자 증감으로만 바꾸므로
 * 이 엔티티로는 갱신하지 않는다. 바뀐 컬럼만 저장해(DynamicUpdate) 판매자의 발급 종료가 동시에 반영된 집계를 이전 값으로 덮어쓰지 않게 한다.
 */
@Entity
@DynamicUpdate
@Table(name = "coupon_templates")
public class CouponTemplate extends AuditableEntity {
  /** 웰컴 혜택: 가입 후 14일 이내, 상품대금 20,000원 이상에 3,000원 할인. */
  public static final long WELCOME_DISCOUNT = 3_000;

  public static final long WELCOME_MIN_ORDER = 20_000;
  public static final Duration WELCOME_VALIDITY = Duration.ofDays(14);
  static final String WELCOME_NAME = "웰컴 쿠폰";

  /** 쿠폰 수단은 총 PG 비용 4% 이하인 카드·간편결제로 한정한다. */
  static final int PG_COST_CEILING_BPS = 400;

  static final long OTHER_COST_ALLOWANCE = 1_000;

  /** SELLER 쿠폰은 할인 후 상품대금이 이 금액 이상이 되도록 설정한다. */
  public static final long SELLER_MIN_AFTER_DISCOUNT = 20_000;

  /** 판매자에게 부과하는 PG·부가세 포함 수수료율(%)이며 기준액은 판매자 쿠폰을 뺀 B다. */
  static final long FEE_PERCENT = 15;

  /** 등급·판매자 쿠폰이 최소 주문금액에서도 남겨야 하는 보수적 잔여액 H의 하한. */
  static final long PROFIT_FLOOR = 500;

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  private CouponKind kind;

  @Enumerated(EnumType.STRING)
  private CouponFunder funder;

  private UUID sellerId;
  private UUID productId;
  private String grade;
  private LocalDate targetMonth;
  private String name;
  private long discountAmount;
  private long minOrderAmount;
  private long minSellerNetAmount;

  @JdbcTypeCode(SqlTypes.ARRAY)
  private String[] allowedMethods;

  private int pgCostCeilingBps;
  private long otherCostAllowance;
  private Integer issuanceLimit;
  private int issuedCount;
  private Long totalBudget;
  private long committedBudget;
  private long consumedBudget;
  private Instant validFrom;
  private Instant validUntil;

  @Enumerated(EnumType.STRING)
  private CouponTemplateStatus status;

  @Version private Long version;

  protected CouponTemplate() {}

  private CouponTemplate(Instant now) {
    super(now);
  }

  /** 서버 규칙으로 만드는 활성 웰컴 캠페인. 종료 없이 유지하며 쿠폰 기한은 가입 시각으로 정한다. */
  public static CouponTemplate welcome(UUID id, Instant now) {
    CouponTemplate template = new CouponTemplate(now);
    template.id = Objects.requireNonNull(id, "id");
    template.kind = CouponKind.WELCOME;
    template.funder = CouponFunder.PLATFORM;
    template.name = WELCOME_NAME;
    template.discountAmount = WELCOME_DISCOUNT;
    template.minOrderAmount = WELCOME_MIN_ORDER;
    template.allowedMethods =
        Arrays.stream(Order.ExternalMethod.values()).map(Enum::name).toArray(String[]::new);
    template.pgCostCeilingBps = PG_COST_CEILING_BPS;
    template.otherCostAllowance = OTHER_COST_ALLOWANCE;
    template.validFrom = truncate(now);
    template.status = CouponTemplateStatus.ACTIVE;
    return template;
  }

  /**
   * 판매자 캠페인. 할인 후 상품대금 20,000원, 최소 주문금액에서의 판매자 정산액과 손익 하한 {@code H=R−C−O≥500}을 검증한다. 판매자 쿠폰은 마일리지와
   * 함께 쓰지 않고 플랫폼 할인이 없으므로 최소 주문금액에서 외부 결제액은 {@code B=G−할인액}이다.
   */
  public static CouponTemplate seller(
      UUID id,
      UUID sellerId,
      UUID productId,
      String name,
      long discountAmount,
      long minOrderAmount,
      int issuanceLimit,
      Instant validFrom,
      Instant validUntil,
      long totalBudget,
      long minSellerNetAmount,
      Set<Order.ExternalMethod> methods,
      Instant now) {
    if (discountAmount < 1 || discountAmount >= minOrderAmount || minOrderAmount > 10_000_000) {
      throw new InvalidCouponTermsException("discountAmount", "할인액은 최소 주문금액보다 작아야 합니다.");
    }
    long base = minOrderAmount - discountAmount;
    if (base < SELLER_MIN_AFTER_DISCOUNT) {
      throw new InvalidCouponTermsException(
          "minOrderAmount", "할인 후 상품대금이 20,000원 이상이 되도록 최소 주문금액을 정해 주세요.");
    }
    long fee = base * FEE_PERCENT / 100;
    if (base - fee < minSellerNetAmount) {
      throw new InvalidCouponTermsException(
          "minSellerNetAmount", "최소 주문금액에서의 판매자 정산액이 승인한 최소 정산액보다 작습니다.");
    }
    long pgCost = (base * PG_COST_CEILING_BPS + 9_999) / 10_000;
    if (fee * 10 / 11 - pgCost - OTHER_COST_ALLOWANCE < PROFIT_FLOOR) {
      throw new InvalidCouponTermsException("discountAmount", "최소 주문금액에서 손익 하한을 지키지 못하는 할인입니다.");
    }
    if (!truncate(validFrom).isBefore(truncate(validUntil))) {
      throw new InvalidCouponTermsException("validUntil", "종료 시각은 시작 시각보다 뒤여야 합니다.");
    }
    if (!now.isBefore(validUntil)) {
      throw new InvalidCouponTermsException("validUntil", "이미 지난 종료 시각입니다.");
    }
    if (issuanceLimit < 1) {
      throw new InvalidCouponTermsException("issuanceLimit", "발급 수는 1 이상이어야 합니다.");
    }
    if (totalBudget < discountAmount) {
      throw new InvalidCouponTermsException("totalBudget", "할인 예산은 할인액 이상이어야 합니다.");
    }
    if (methods.isEmpty()) {
      throw new InvalidCouponTermsException("allowedMethods", "사용 수단을 하나 이상 정해 주세요.");
    }
    CouponTemplate template = new CouponTemplate(now);
    template.id = Objects.requireNonNull(id, "id");
    template.kind = CouponKind.SELLER;
    template.funder = CouponFunder.SELLER;
    template.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    template.productId = productId;
    template.name = Objects.requireNonNull(name, "name");
    template.discountAmount = discountAmount;
    template.minOrderAmount = minOrderAmount;
    template.minSellerNetAmount = minSellerNetAmount;
    template.allowedMethods =
        Arrays.stream(Order.ExternalMethod.values())
            .filter(methods::contains)
            .map(Enum::name)
            .toArray(String[]::new);
    template.pgCostCeilingBps = PG_COST_CEILING_BPS;
    template.otherCostAllowance = OTHER_COST_ALLOWANCE;
    template.issuanceLimit = issuanceLimit;
    template.totalBudget = totalBudget;
    template.validFrom = truncate(validFrom);
    template.validUntil = truncate(validUntil);
    template.status = CouponTemplateStatus.ACTIVE;
    return template;
  }

  /** 신규 발급만 막는다. 이미 발급한 쿠폰의 조건·기한은 그대로다. */
  public void close(long expectedVersion, Instant now) {
    if (status != CouponTemplateStatus.ACTIVE) {
      throw new CouponCampaignConflictException(Reason.CLOSED);
    }
    if (expectedVersion != getVersion()) {
      throw new VersionConflictException("coupon template", expectedVersion, getVersion());
    }
    status = CouponTemplateStatus.CLOSED;
    touch(now);
  }

  /** 판매자 쿠폰 발급 가능 여부. 발급 수·예산은 저장소의 원자 증감이 함께 검사한다. */
  public void requireIssuableAt(Instant now) {
    if (status != CouponTemplateStatus.ACTIVE) {
      throw new CouponCampaignConflictException(Reason.CLOSED);
    }
    if (now.isBefore(validFrom)) {
      throw new CouponCampaignConflictException(Reason.NOT_STARTED);
    }
    if (!now.isBefore(validUntil)) {
      throw new CouponCampaignConflictException(Reason.ENDED);
    }
  }

  public boolean isSellerCampaignOf(UUID sellerId) {
    return kind == CouponKind.SELLER && this.sellerId.equals(sellerId);
  }

  public boolean isActive() {
    return status == CouponTemplateStatus.ACTIVE;
  }

  public UUID getId() {
    return id;
  }

  public CouponKind getKind() {
    return kind;
  }

  public CouponFunder getFunder() {
    return funder;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public UUID getProductId() {
    return productId;
  }

  public LocalDate getTargetMonth() {
    return targetMonth;
  }

  public String getName() {
    return name;
  }

  public long getDiscountAmount() {
    return discountAmount;
  }

  public long getMinOrderAmount() {
    return minOrderAmount;
  }

  public List<Order.ExternalMethod> getAllowedMethods() {
    return Arrays.stream(allowedMethods).map(Order.ExternalMethod::valueOf).toList();
  }

  public int getPgCostCeilingBps() {
    return pgCostCeilingBps;
  }

  public long getOtherCostAllowance() {
    return otherCostAllowance;
  }

  public long getMinSellerNetAmount() {
    return minSellerNetAmount;
  }

  public Integer getIssuanceLimit() {
    return issuanceLimit;
  }

  public Long getTotalBudget() {
    return totalBudget;
  }

  public int getIssuedCount() {
    return issuedCount;
  }

  public long getCommittedBudget() {
    return committedBudget;
  }

  public long getConsumedBudget() {
    return consumedBudget;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidUntil() {
    return validUntil;
  }

  public CouponTemplateStatus getStatus() {
    return status;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
