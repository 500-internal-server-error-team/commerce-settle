package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.Product;
import java.util.Optional;
import java.util.UUID;

/** 목록은 createdAt DESC, id DESC 순서이며 논리삭제 상품을 제외한다. */
public interface ProductRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 동시 변경 충돌을 드러낸다. */
  Product save(Product product);

  /** 논리삭제 상품도 돌려준다. 삭제 상품을 제외할지는 호출 쪽이 정한다. */
  Optional<Product> findById(UUID productId);

  /** 재고 잠금 뒤 주문 견적을 재검증한다. 영속성 컨텍스트를 재사용하지 않고 DB의 판매 가능 버전을 읽는다. */
  Optional<Long> findOrderableVersion(UUID productId);

  PageResult<Product> findBySeller(UUID sellerId, int page, int size);

  PageResult<Product> findOnSale(int page, int size);
}
