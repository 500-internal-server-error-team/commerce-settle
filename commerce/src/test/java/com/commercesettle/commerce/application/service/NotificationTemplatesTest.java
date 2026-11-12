package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.port.NotificationStore.Draft;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationTemplatesTest {
  private static final UUID ORDER = UUID.fromString("00000000-0000-4000-8000-000000000123");
  private static final long BALANCE = 4234567890123456789L;

  @ParameterizedTest
  @EnumSource(Notification.Kind.class)
  @DisplayName("최대 금액·긴 상품명에서도 역할·거래 값·상태·시각을 200자 안에 보존한다")
  void preservesTransactionFactsWithinProviderLimit(Notification.Kind kind) {
    ActorKind actor =
        Set.of(
                    Notification.Kind.SALE_COMPLETED,
                    Notification.Kind.SETTLEMENT_READY,
                    Notification.Kind.PAYOUT_COMPLETED,
                    Notification.Kind.PAYOUT_ACTION_REQUIRED)
                .contains(kind)
            ? ActorKind.SELLER
            : ActorKind.BUYER;
    var rendered = NotificationTemplates.render(draft(kind, actor), 2, Long.MAX_VALUE);

    assertThat(rendered.body().codePointCount(0, rendered.body().length()))
        .isLessThanOrEqualTo(200);
    assertThat(rendered.body().length()).isLessThanOrEqualTo(200);
    assertThat(rendered.body())
        .startsWith(actor == ActorKind.BUYER ? "[구매자]" : "[판매자]")
        .contains("시각 11-02 10:00 KST", rendered.title())
        .doesNotContain(ORDER.toString());
    if (kind != Notification.Kind.PAYMENT_COMPLETED)
      assertThat(rendered.body())
          .contains(String.format(java.util.Locale.KOREA, "%,d", Long.MAX_VALUE));
  }

  @Test
  @DisplayName("판매자 환불 본문에는 구매자 마일리지 복원액과 잔액을 포함하지 않는다")
  void excludesWalletFactsFromSellerRefund() {
    var body =
        NotificationTemplates.render(
                draft(Notification.Kind.REFUND_COMPLETED, ActorKind.SELLER), 1, 0)
            .body();

    assertThat(body)
        .startsWith("[판매자]")
        .contains("환불", String.format(java.util.Locale.KOREA, "%,d", Long.MAX_VALUE))
        .doesNotContain("마일리지", "잔액", String.format(java.util.Locale.KOREA, "%,d", BALANCE));
  }

  private Draft draft(Notification.Kind kind, ActorKind actor) {
    var p =
        new ObjectMapper()
            .createObjectNode()
            .put("orderId", ORDER.toString())
            .put("productName", "🍊".repeat(60))
            .put("totalAmount", Long.MAX_VALUE)
            .put("externalAmount", Long.MAX_VALUE / 2)
            .put("mileageAmount", Long.MAX_VALUE - Long.MAX_VALUE / 2)
            .put("mileageBalanceAfter", BALANCE)
            .put("walletVersion", 1)
            .put("amount", Long.MAX_VALUE)
            .put("batchDate", "2026-11-02")
            .put("cutoffAt", "2026-11-02T00:00:00+09:00")
            .put("succeededAt", "2026-11-02T10:00:00+09:00")
            .put("decision", "APPROVED");
    return new Draft(
        "commerce",
        UUID.randomUUID(),
        kind,
        ORDER,
        actor,
        UUID.randomUUID(),
        Instant.parse("2026-11-02T01:00:00Z"),
        p,
        new Notification.Destination(Notification.Channel.KAKAO_SELF, "a".repeat(64)));
  }
}
