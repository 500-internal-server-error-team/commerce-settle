package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.exception.PayoutNotAllowedException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayoutRecipientTest {
  private static final UUID SELLER = UUID.randomUUID();
  private static final UUID PRODUCT_A = UUID.randomUUID();
  private static final UUID PRODUCT_B = UUID.randomUUID();

  @Test
  @DisplayName("모든 상품이 판매자 소유이고 계정 사본이 같으면 그 계정으로 지급한다")
  void resolvesSingleRecipient() {
    PayoutRecipient recipient =
        PayoutRecipient.resolve(
            SELLER,
            Set.of(PRODUCT_A, PRODUCT_B),
            List.of(
                context(PRODUCT_A, SELLER, true, "acc", 2),
                context(PRODUCT_B, SELLER, true, "acc", 2)));

    assertThat(recipient).isEqualTo(new PayoutRecipient("acc", 2));
  }

  @Test
  @DisplayName("다른 판매자의 상품이 섞이면 거절한다")
  void rejectsProductOfAnotherSeller() {
    assertRejected(List.of(context(PRODUCT_A, UUID.randomUUID(), true, "acc", 1)));
  }

  @Test
  @DisplayName("판매자가 지급 불가 상태면 거절한다")
  void rejectsSellerNotAllowedToReceive() {
    assertRejected(List.of(context(PRODUCT_A, SELLER, false, "acc", 1)));
  }

  @Test
  @DisplayName("상품마다 받은 수취 계정 사본이 다르면 거절한다")
  void rejectsDifferentAccountCopies() {
    assertThatThrownBy(
            () ->
                PayoutRecipient.resolve(
                    SELLER,
                    Set.of(PRODUCT_A, PRODUCT_B),
                    List.of(
                        context(PRODUCT_A, SELLER, true, "acc", 1),
                        context(PRODUCT_B, SELLER, true, "acc", 2))))
        .isInstanceOf(PayoutNotAllowedException.class);
  }

  @Test
  @DisplayName("상품의 지급 컨텍스트를 받지 못하면 거절한다")
  void rejectsMissingContext() {
    assertRejected(List.of());
  }

  private static void assertRejected(List<ProductSettlementContext> contexts) {
    assertThatThrownBy(() -> PayoutRecipient.resolve(SELLER, Set.of(PRODUCT_A), contexts))
        .isInstanceOf(PayoutNotAllowedException.class);
  }

  private static ProductSettlementContext context(
      UUID productId, UUID sellerId, boolean allowed, String accountRef, long accountVersion) {
    return new ProductSettlementContext(productId, sellerId, allowed, accountRef, accountVersion);
  }
}
