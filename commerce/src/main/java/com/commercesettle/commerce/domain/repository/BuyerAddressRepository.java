package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.BuyerAddress;
import java.util.Optional;
import java.util.UUID;

/** 목록은 기본 주소를 먼저, 그다음 createdAt DESC, id DESC 순서이며 논리삭제 주소를 제외한다. */
public interface BuyerAddressRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 기본 주소 유일성·동시 변경 충돌을 드러낸다. */
  BuyerAddress save(BuyerAddress address);

  Optional<BuyerAddress> findById(UUID addressId);

  Optional<BuyerAddress> findDefault(UUID buyerId);

  boolean existsActive(UUID buyerId);

  PageResult<BuyerAddress> findByBuyer(UUID buyerId, int page, int size);
}
