package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.Product;
import java.util.UUID;

/** 일반 주문에서 재고 행을 먼저 확보한 뒤 상품을 확인하는 계약. */
public interface OrderInventory {
  /**
   * 실제 DB 구현은 호출자의 주문 생성 트랜잭션에 참여하고, 트랜잭션 밖 호출은 거절한다. product_inventory 행을 배타 잠금한 뒤 상품을 읽으며, 별도로
   * 커밋하지 않고 호출자의 커밋·롤백까지 잠금을 유지한다. 판매 가능 여부·가격·버전 검증과 주문 사본 생성은 주문 응용 서비스가 맡는다. 반환된 상품은 변경하지 않는다.
   * 상품이 없으면 ProductNotFoundException으로 거절한다.
   *
   * <p>개발용 메모리 구현은 상품 샘플만 반환하며 이 DB 트랜잭션·잠금 보장을 제공하지 않는다.
   */
  Product lockInventoryAndReadProduct(UUID productId);
}
