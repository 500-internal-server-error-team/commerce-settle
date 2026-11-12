package com.commercesettle.financemock.presentation.dto;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransactionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** 원대역 거래. succeededAt은 최초 성공 시각이며 성공 전 null, 성공 후 바뀌지 않는다. PG 입금 필드는 APPROVE에서만 쓴다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MockTransactionResponse(
    UUID id,
    String providerId,
    MockOperation operation,
    UUID businessKey,
    UUID originalId,
    long amount,
    MockTransactionStatus status,
    String scenarioVersion,
    OffsetDateTime createdAt,
    OffsetDateTime succeededAt,
    OffsetDateTime pgReceiptDueAt,
    OffsetDateTime pgReceivedAt,
    Long pgReceivedAmount,
    String fundingAccountRef) {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  public static MockTransactionResponse from(MockTransactionView view) {
    var transaction = view.transaction();
    return new MockTransactionResponse(
        transaction.getId(),
        transaction.getProviderId(),
        transaction.getOperation(),
        transaction.getBusinessKey(),
        transaction.getOriginalId(),
        transaction.getAmount(),
        transaction.getStatus(),
        transaction.getScenarioVersion(),
        kst(transaction.getCreatedAt()),
        kst(transaction.getSucceededAt()),
        kst(transaction.getPgReceiptDueAt()),
        kst(transaction.getPgReceivedAt()),
        transaction.getPgReceivedAmount(),
        view.fundingAccountRef());
  }

  private static OffsetDateTime kst(Instant time) {
    return time == null ? null : time.atZone(KST).toOffsetDateTime();
  }
}
