package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SettlementBatchRepository {
  /** 즉시 DB에 반영한다. 같은 판매자·날짜·정책 버전의 배치가 먼저 저장됐으면 유니크 제약에서 실패한다. */
  SettlementBatch save(SettlementBatch batch);

  Optional<SettlementBatch> findById(UUID id);

  List<SettlementBatch> findAllById(Set<UUID> ids);

  Optional<SettlementBatch> find(UUID sellerId, LocalDate batchDate, String policyVersion);

  /** 판매자의 배치을 생성 시각·ID 내림차순으로 한 페이지 읽는다. */
  List<SettlementBatch> findBySeller(UUID sellerId, int page, int size);

  long countBySeller(UUID sellerId);
}
