package com.commercesettle.settlement.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 정산 원장 한 줄. 추가만 하고 고치지 않는다. 시각은 원천 이벤트의 ID·occurredAt을 그대로 보존해 늦은 반영·재전달에도 바뀌지 않는다. */
@Entity
@Table(name = "settlement_entries")
public class SettlementEntry {
  @Id private UUID id;
  private UUID itemId;
  private UUID paymentId;

  @Enumerated(EnumType.STRING)
  private EntrySide side;

  @Enumerated(EnumType.STRING)
  private EntryLeg leg;

  private long amount;
  private UUID businessRef;
  private UUID sourceEventId;
  private Instant sourceOccurredAt;
  private UUID reversalOf;
  private String policyVersion;
  private Instant createdAt;

  protected SettlementEntry() {}

  private SettlementEntry(
      SettlementItem item,
      EntryLeg leg,
      long amount,
      UUID sourceEventId,
      Instant sourceOccurredAt,
      String policyVersion,
      Instant now) {
    this.id = UUID.randomUUID();
    this.itemId = item.getId();
    this.paymentId = item.getPaymentId();
    this.side = leg.side();
    this.leg = leg;
    this.amount = amount;
    this.businessRef = item.getPaymentId();
    this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
    this.sourceOccurredAt =
        Objects.requireNonNull(sourceOccurredAt, "sourceOccurredAt").truncatedTo(ChronoUnit.MICROS);
    this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
  }

  private SettlementEntry(
      SettlementEntry original,
      UUID refundId,
      UUID sourceEventId,
      Instant sourceOccurredAt,
      Instant now) {
    this.id = UUID.randomUUID();
    this.itemId = original.itemId;
    this.paymentId = original.paymentId;
    this.side = original.side;
    this.leg = original.leg;
    this.amount = -original.amount;
    this.businessRef = Objects.requireNonNull(refundId, "refundId");
    this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
    this.sourceOccurredAt =
        Objects.requireNonNull(sourceOccurredAt, "sourceOccurredAt").truncatedTo(ChronoUnit.MICROS);
    this.reversalOf = original.id;
    // 원 줄의 정책 버전을 그대로 둔다. 중복 결제 판정은 최초 줄(reversal_of IS NULL)에서만 정책 버전을 읽는다.
    this.policyVersion = original.policyVersion;
    this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * 전액 환불의 상쇄 줄을 최초 원장 줄마다 하나씩 반대 부호로 만든다. 0원 leg도 상쇄해 원 줄과 1:1로 대응한다. business_ref는 refundId다. 최초
   * 줄이 아니거나 5 leg가 모두 있지 않으면 거절한다.
   */
  public static List<SettlementEntry> reversals(
      List<SettlementEntry> originals,
      UUID refundId,
      UUID sourceEventId,
      Instant sourceOccurredAt,
      Instant now) {
    if (originals.size() != EntryLeg.values().length
        || originals.stream().anyMatch(entry -> entry.reversalOf != null)
        || originals.stream().map(SettlementEntry::getLeg).distinct().count()
            != EntryLeg.values().length) {
      throw new IllegalArgumentException("Reversal requires the five original ledger legs");
    }
    return originals.stream()
        .map(
            original ->
                new SettlementEntry(original, refundId, sourceEventId, sourceOccurredAt, now))
        .toList();
  }

  /**
   * 결제 확정 때의 최초 원장 5 leg를 항목 금액 사본에서 만든다. 0원 leg도 기록해 환불 상쇄가 원 leg마다 반대 부호 1건으로 대응하게 한다. 최초 원장의
   * business_ref는 paymentId다.
   */
  public static List<SettlementEntry> initial(
      SettlementItem item,
      UUID sourceEventId,
      Instant sourceOccurredAt,
      String policyVersion,
      Instant now) {
    SettlementAmounts amounts = item.getAmounts();
    return List.of(
        new SettlementEntry(
            item,
            EntryLeg.EXTERNAL,
            amounts.externalAmount(),
            sourceEventId,
            sourceOccurredAt,
            policyVersion,
            now),
        new SettlementEntry(
            item,
            EntryLeg.MILEAGE,
            amounts.mileageAmount(),
            sourceEventId,
            sourceOccurredAt,
            policyVersion,
            now),
        new SettlementEntry(
            item,
            EntryLeg.PLATFORM_COUPON,
            amounts.platformCouponAmount(),
            sourceEventId,
            sourceOccurredAt,
            policyVersion,
            now),
        new SettlementEntry(
            item,
            EntryLeg.SELLER,
            amounts.netAmount(),
            sourceEventId,
            sourceOccurredAt,
            policyVersion,
            now),
        new SettlementEntry(
            item,
            EntryLeg.FEE,
            amounts.feeAmount(),
            sourceEventId,
            sourceOccurredAt,
            policyVersion,
            now));
  }

  public UUID getId() {
    return id;
  }

  public UUID getItemId() {
    return itemId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public EntrySide getSide() {
    return side;
  }

  public EntryLeg getLeg() {
    return leg;
  }

  public long getAmount() {
    return amount;
  }

  public UUID getBusinessRef() {
    return businessRef;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }

  public Instant getSourceOccurredAt() {
    return sourceOccurredAt;
  }

  public UUID getReversalOf() {
    return reversalOf;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
