package com.commercesettle.settlement.domain.model;

import com.commercesettle.settlement.domain.exception.PayoutNotAllowedException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 지급에 고정할 수취 계정 사본. 판매자 항목 전체에서 하나로 정해져야 한다. */
public record PayoutRecipient(String accountRef, long accountVersion) {

  /**
   * 지급 전 검증. 대상 항목의 모든 상품이 그 판매자 소유이고, 판매자가 지급 가능하며, 상품마다 받은 수취 계정 사본이 하나로 일치해야 한다.
   *
   * @throws PayoutNotAllowedException 하나라도 어긋나면
   */
  public static PayoutRecipient resolve(
      UUID sellerId, Set<UUID> productIds, Collection<ProductSettlementContext> contexts) {
    Map<UUID, ProductSettlementContext> byProduct = new HashMap<>();
    contexts.forEach(context -> byProduct.put(context.productId(), context));
    PayoutRecipient recipient = null;
    for (UUID productId : productIds) {
      ProductSettlementContext context = byProduct.get(productId);
      if (context == null) {
        throw new PayoutNotAllowedException("product settlement context is missing");
      }
      if (!sellerId.equals(context.sellerId())) {
        throw new PayoutNotAllowedException("product does not belong to the seller");
      }
      if (!context.payoutAllowed()) {
        throw new PayoutNotAllowedException("seller is not allowed to receive payouts");
      }
      if (context.recipientAccountRef() == null || context.recipientAccountRef().isBlank()) {
        throw new PayoutNotAllowedException("recipient account is missing");
      }
      PayoutRecipient current =
          new PayoutRecipient(context.recipientAccountRef(), context.recipientAccountVersion());
      if (recipient != null && !recipient.equals(current)) {
        throw new PayoutNotAllowedException("recipient account copies differ between products");
      }
      recipient = current;
    }
    if (recipient == null) {
      throw new PayoutNotAllowedException("no product to pay out");
    }
    return recipient;
  }
}
