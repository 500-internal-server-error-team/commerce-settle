package com.commercesettle.settlement.domain.model;

import java.util.UUID;

/**
 * 커머스가 제공하는 상품·판매자 지급 컨텍스트. 지급 생성 직전에 조회하며 판매자 정보의 소유권은 커머스에 남는다.
 *
 * @param payoutAllowed 판매자 상태가 지급 가능한지
 */
public record ProductSettlementContext(
    UUID productId,
    UUID sellerId,
    boolean payoutAllowed,
    String recipientAccountRef,
    long recipientAccountVersion) {}
