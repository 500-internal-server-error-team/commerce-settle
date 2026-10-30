package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.MileageEntry;

/** 마일리지 원장은 추가만 한다. 수정·삭제 메서드를 두지 않는다. */
public interface MileageEntryRepository {
  MileageEntry save(MileageEntry entry);
}
