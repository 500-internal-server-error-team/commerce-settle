package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageEntryKind;
import java.time.Instant;
import java.util.UUID;

/** 마일리지 원장 한 줄. 최초 지급(SEED)은 결제가 없어 paymentId가 null이다. */
public record MileageEntryView(
    UUID id,
    UUID paymentId,
    MileageEntryKind kind,
    long amount,
    long balanceAfter,
    Instant createdAt) {
  public static MileageEntryView of(MileageEntry entry) {
    return new MileageEntryView(
        entry.getId(),
        entry.getPaymentId(),
        entry.getKind(),
        entry.getAmount(),
        entry.getBalanceAfter(),
        entry.getCreatedAt());
  }
}
