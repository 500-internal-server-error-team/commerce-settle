package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 커머스의 상품·판매자 지급 컨텍스트 조회(`GET /internal/products/{productId}/settlement-context`) 계약. */
public interface ProductSettlementContextReader {
  /** 판매자 항목에 나온 상품마다 컨텍스트를 돌려준다. 찾지 못한 상품은 결과에서 빠진다. */
  List<ProductSettlementContext> read(UUID sellerId, Set<UUID> productIds);
}
