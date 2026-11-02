package com.commercesettle.commerce.domain.model;

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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 발급 당시 혜택 조건과 할인 비용 집계. 혜택 조건은 발급 뒤 바꾸지 않는다. 발급 수·약속액·사용액은 발급·사용·복구와 같은 트랜잭션에서 저장소의 원자 증감으로만 바꾸므로
 * 이 엔티티로는 갱신하지 않는다.
 */
@Entity
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
