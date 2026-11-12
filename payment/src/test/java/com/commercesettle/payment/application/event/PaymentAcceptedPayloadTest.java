package com.commercesettle.payment.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAcceptedPayloadTest {

  private static final UUID PAYMENT_ID = new UUID(0, 1);
  private static final UUID ORDER_ID = new UUID(0, 2);
  private static final UUID CYCLE_ID = new UUID(0, 3);

  @Test
  @DisplayName("OpenAPI 계약의 4필드 이름으로 직렬화한다")
  void serializesContractFields() {
    JsonNode json =
        new ObjectMapper()
            .valueToTree(new PaymentAcceptedPayload(PAYMENT_ID, ORDER_ID, 3, CYCLE_ID));

    assertThat(json.properties())
        .extracting(entry -> entry.getKey())
        .containsExactlyInAnyOrder("paymentId", "orderId", "orderVersion", "paymentCycleId");
    assertThat(json.get("paymentId").asText()).isEqualTo(PAYMENT_ID.toString());
    assertThat(json.get("orderVersion").asLong()).isEqualTo(3);
  }

  @Test
  @DisplayName("주문 버전 0은 허용한다")
  void allowsInitialOrderVersion() {
    assertThat(new PaymentAcceptedPayload(PAYMENT_ID, ORDER_ID, 0, CYCLE_ID).orderVersion())
        .isZero();
  }

  @Test
  @DisplayName("결제·주문·결제 회차 ID가 없으면 거절한다")
  void rejectsMissingIds() {
    assertThatThrownBy(() -> new PaymentAcceptedPayload(null, ORDER_ID, 0, CYCLE_ID))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("paymentId");
    assertThatThrownBy(() -> new PaymentAcceptedPayload(PAYMENT_ID, null, 0, CYCLE_ID))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("orderId");
    assertThatThrownBy(() -> new PaymentAcceptedPayload(PAYMENT_ID, ORDER_ID, 0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("paymentCycleId");
  }

  @Test
  @DisplayName("음수 주문 버전은 거절한다")
  void rejectsNegativeOrderVersion() {
    assertThatThrownBy(() -> new PaymentAcceptedPayload(PAYMENT_ID, ORDER_ID, -1, CYCLE_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
