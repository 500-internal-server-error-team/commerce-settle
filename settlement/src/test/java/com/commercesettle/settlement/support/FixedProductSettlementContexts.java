package com.commercesettle.settlement.support;

import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 커머스를 띄우지 않는 Settlement 통합 시험용 지급 컨텍스트. 항목의 판매자를 지급 가능으로 보고 판매자마다 고정한 시험 계정을 돌려준다. 컨텍스트 판정 자체는
 * {@code HttpProductSettlementContextReaderTest}·{@code DailySettlementJobIntegrationTest}가 검증한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedProductSettlementContexts {
  @Bean
  @Primary
  ProductSettlementContextReader fixedProductSettlementContextReader() {
    return (sellerId, productIds) ->
        productIds.stream()
            .map(
                productId ->
                    new ProductSettlementContext(
                        productId, sellerId, true, "mock-seller-" + sellerId, 1))
            .toList();
  }
}
