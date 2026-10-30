package com.commercesettle.settlement.infrastructure.client;

import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 커머스의 settlement-context API가 생기기 전까지 쓰는 개발용 구현. 정산 항목의 판매자를 그대로 믿고, 판매자마다 고정한 시험 수취 계정을 돌려준다. 상품
 * 소유·판매자 상태를 실제로 확인하지 않으므로 HTTP 어댑터로 바꾸기 전에는 로컬 개발에서만 쓴다.
 */
@Component
class ConfiguredProductSettlementContextReader implements ProductSettlementContextReader {
  private static final long ACCOUNT_VERSION = 1;

  @Override
  public List<ProductSettlementContext> read(UUID sellerId, Set<UUID> productIds) {
    return productIds.stream()
        .map(
            productId ->
                new ProductSettlementContext(
                    productId, sellerId, true, "mock-seller-" + sellerId, ACCOUNT_VERSION))
        .toList();
  }
}
