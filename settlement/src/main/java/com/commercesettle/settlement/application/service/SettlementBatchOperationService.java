package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.DailySettlementSettings;
import com.commercesettle.settlement.application.exception.OperationRejectedException;
import com.commercesettle.settlement.application.exception.OperationRejectedException.Reason;
import com.commercesettle.settlement.application.port.AuditLog;
import com.commercesettle.settlement.application.service.SellerSettlementService.Settled;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자가 판매자 한 명·날짜·정책 버전의 일배치를 만들거나 기존 배치를 돌려받는다. 일배치와 같은 {@link SellerSettlementService}를 요청 트랜잭션에서
 * 실행하며 일배치 잠금은 잡지 않는다. 같은 판매자를 일배치가 동시에 처리하면 항목 조건부 선점이 한쪽만 통과시키고, 선점하지 못한 쪽은 먼저 커밋된 배치를 다시 읽는다.
 */
@Service
public class SettlementBatchOperationService {
  static final String CREATE = "SETTLEMENT_BATCH_CREATE";

  private final SettlementBatchRepository batches;
  private final SellerSettlementService sellers;
  private final DailySettlementSettings settings;
  private final IdempotentRequests idempotent;
  private final AuditLog audit;
  private final Clock clock;

  public SettlementBatchOperationService(
      SettlementBatchRepository batches,
      SellerSettlementService sellers,
      DailySettlementSettings settings,
      IdempotentRequests idempotent,
      AuditLog audit,
      Clock clock) {
    this.batches = batches;
    this.sellers = sellers;
    this.settings = settings;
    this.idempotent = idempotent;
    this.audit = audit;
    this.clock = clock;
  }

  /** 같은 키·본문의 재요청은 그 배치의 현재 상태를 돌려준다. 대상 고정 뒤 항목을 추가하지 않는다. */
  @Transactional
  public SettlementBatch create(
      String operator, String key, UUID sellerId, LocalDate batchDate, String policyVersion) {
    return idempotent.execute(
        new IdempotentRequests.Request(
            "OPERATOR", operator, CREATE, key, List.of(sellerId, batchDate, policyVersion)),
        202,
        () -> createOrFind(operator, sellerId, batchDate, policyVersion),
        SettlementBatch::getId,
        id -> batches.findById(id).orElseThrow());
  }

  private SettlementBatch createOrFind(
      String operator, UUID sellerId, LocalDate batchDate, String policyVersion) {
    if (!settings.policyVersion().equals(policyVersion)) {
      throw new OperationRejectedException(Reason.POLICY_UNSUPPORTED);
    }
    Instant now = clock.instant();
    if (SettlementBatch.cutoffOf(batchDate).isAfter(now)) {
      throw new OperationRejectedException(Reason.BATCH_NOT_DUE);
    }
    Optional<SettlementBatch> existing = batches.find(sellerId, batchDate, policyVersion);
    if (existing.isPresent()) {
      return existing.get();
    }
    Optional<Settled> settled = sellers.settleReportingCreation(sellerId, batchDate);
    if (settled.isEmpty()) {
      // 선점할 항목이 없었다. 같은 판매자를 먼저 처리한 일배치가 커밋했으면 그 배치를 돌려준다.
      return batches
          .find(sellerId, batchDate, policyVersion)
          .orElseThrow(() -> new OperationRejectedException(Reason.NO_ELIGIBLE_ITEMS));
    }
    SettlementBatch batch = settled.get().batch();
    if (!settled.get().created()) {
      // 위 조회 뒤 일배치가 먼저 만든 배치다. 이 요청의 생성이 아니므로 감사하지 않는다.
      return batch;
    }
    audit.record(
        new AuditLog.Entry(
            "OPERATOR",
            operator,
            AuditLog.Action.SETTLEMENT_BATCH_CREATED,
            "SETTLEMENT_BATCH",
            batch.getId(),
            null,
            batch.getStatus().name(),
            "OPERATOR_REQUEST",
            batch.getId().toString(),
            now));
    return batch;
  }
}
