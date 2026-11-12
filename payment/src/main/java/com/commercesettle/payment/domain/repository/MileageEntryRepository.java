package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.MileageEntry;
import java.util.List;
import java.util.UUID;

/** 마일리지 원장은 추가만 한다. 수정·삭제 메서드를 두지 않는다. */
public interface MileageEntryRepository {
  MileageEntry save(MileageEntry entry);

  /** 지갑의 원장을 기록 시각·ID 내림차순으로 한 페이지 읽는다. */
  List<MileageEntry> findByWalletId(UUID walletId, int page, int size);

  long countByWalletId(UUID walletId);
}
