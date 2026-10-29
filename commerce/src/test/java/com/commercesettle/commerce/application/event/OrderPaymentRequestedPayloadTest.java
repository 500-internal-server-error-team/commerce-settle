package com.commercesettle.commerce.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class OrderPaymentRequestedPayloadTest {

  @Autowired private ObjectMapper mapper;

  @Test
  @DisplayName("이벤트 시각은 같은 절대시각을 KST 오프셋으로 출력한다")
  void serializesEventTimesInKst() throws Exception {
    var payload = payload(10_000, 0, null, null);
    var occurredAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "OrderPaymentRequested",
            1,
            occurredAt,
            payload.orderId(),
            payload.orderVersion(),
            payload);

    var json = mapper.readTree(mapper.writeValueAsBytes(event));

    assertThat(json.path("occurredAt").asText()).isEqualTo("2026-01-01T09:00:00+09:00");
    assertThat(json.path("payload").path("paymentWaitUntil").asText())
        .isEqualTo("2026-01-01T09:10:00+09:00");
    assertThat(OffsetDateTime.parse(json.path("occurredAt").asText()).toInstant())
        .isEqualTo(occurredAt.toInstant());
  }

  @Test
  @DisplayName("전액 마일리지 결제는 외부 결제 정보가 없어도 된다")
  void allowsMileageOnlyWithoutExternalDetails() {
    assertThatCode(() -> payload(10_000, 0, null, null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("외부 결제액이 있으면 결제수단이 필요하다")
  void rejectsExternalPaymentWithoutMethod() {
    assertThatThrownBy(() -> payload(0, 10_000, null, "mock-card"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("externalMethod");
  }

  @Test
  @DisplayName("외부 결제액이 있으면 시험 계정 참조가 필요하다")
  void rejectsExternalPaymentWithoutAccount() {
    assertThatThrownBy(() -> payload(0, 10_000, ExternalMethod.CREDIT_CARD, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("methodAccountRef");
  }

  private static OrderPaymentRequestedPayload payload(
      long mileageAmount,
      long externalAmount,
      ExternalMethod externalMethod,
      String methodAccountRef) {
    return new OrderPaymentRequestedPayload(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        UUID.fromString("00000000-0000-0000-0000-000000000003"),
        UUID.fromString("00000000-0000-0000-0000-000000000004"),
        UUID.fromString("00000000-0000-0000-0000-000000000005"),
        0,
        Status.PAYMENT_PENDING,
        "test-v1",
        OffsetDateTime.parse("2026-01-01T00:10:00Z"),
        externalMethod,
        methodAccountRef,
        10_000,
        0,
        10_000,
        mileageAmount,
        externalAmount,
        0,
        0,
        10_000);
  }
}
