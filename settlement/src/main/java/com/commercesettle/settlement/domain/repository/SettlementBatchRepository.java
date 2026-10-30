package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository {
  /** 즉시 DB에 반영한다. 같은 판매자·날짜·정책 버전의 배치가 먼저 저장됐으면 유니크 제약에서 실패한다. */
  SettlementBatch save(SettlementBatch batch);

  Optional<SettlementBatch> find(UUID sellerId, LocalDate batchDate, String policyVersion);
}
