package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.QuarantineStore;
import com.commercesettle.payment.application.service.QuarantineService;
import com.commercesettle.payment.infrastructure.config.SettlementReplyListenerConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.Acknowledgment;

@JsonTest
@ExtendWith(MockitoExtension.class)
class SettlementReplyListenerTest {
  private static final String TOPIC = "commerce-settle.settlement.v1";
  private static final String REFUND_ID = "00000000-0000-0000-0000-0000000000a1";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-0000000000b1";
  private static final String RESERVATION_ID = "00000000-0000-0000-0000-0000000000c1";
  @Autowired private ObjectMapper mapper;
  @Mock private OrderPaymentRequestAcceptor acceptor;
  @Mock private QuarantineService quarantine;
  @Mock private Acknowledgment acknowledgment;

  @Test
  @DisplayName("예약 결과의 원본 위치와 본문을 인계한 뒤에만 offset을 승인한다")
  @SuppressWarnings("unchecked")
  void acknowledgesReservationResultAfterAcceptance() {
    byte[] body = RESERVED_JSON.getBytes(StandardCharsets.UTF_8);

    listener(null).receive(new ConsumerRecord<>(TOPIC, 1, 42L, PAYMENT_ID, body), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    var source = ArgumentCaptor.forClass(OrderPaymentRequestAcceptor.Source.class);
    var order = inOrder(acceptor, acknowledgment);
    order.verify(acceptor).acceptRefundEvent(event.capture(), source.capture());
    order.verify(acknowledgment).acknowledge();
    assertThat(event.getValue().eventType()).isEqualTo("RefundReservationResult");
    assertThat(event.getValue().aggregateId()).isEqualTo(UUID.fromString(PAYMENT_ID));
    assertThat(event.getValue().payload())
        .isEqualTo(
            new RefundReservationResultPayload(
                UUID.fromString(REFUND_ID),
                UUID.fromString(PAYMENT_ID),
                RefundReservationResultPayload.Result.RESERVED,
                UUID.fromString(RESERVATION_ID),
                null));
    assertThat(source.getValue().topic()).isEqualTo(TOPIC);
    assertThat(source.getValue().partition()).isEqualTo(1);
    assertThat(source.getValue().offset()).isEqualTo(42L);
    assertThat(source.getValue().body()).containsExactly(body);
  }

  @Test
  @DisplayName("거절 결과는 예약 ID 없이 사유와 함께 인계한다")
  @SuppressWarnings("unchecked")
  void acceptsRejectedReservationResult() throws Exception {
    ObjectNode json = (ObjectNode) mapper.readTree(RESERVED_JSON);
    ObjectNode payload = (ObjectNode) json.get("payload");
    payload.put("result", "REJECTED").putNull("reservationId").put("reasonCode", "PAYOUT_RESERVED");

    listener(null).receive(record(json, PAYMENT_ID), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(acceptor).acceptRefundEvent(event.capture(), any());
    verify(acknowledgment).acknowledge();
    assertThat(event.getValue().payload())
        .isEqualTo(
            new RefundReservationResultPayload(
                UUID.fromString(REFUND_ID),
                UUID.fromString(PAYMENT_ID),
                RefundReservationResultPayload.Result.REJECTED,
                null,
                "PAYOUT_RESERVED"));
  }

  @Test
  @DisplayName("원장 상쇄 확인과 예약 해제 확인을 인계한다")
  @SuppressWarnings("unchecked")
  void acceptsLedgerReversedAndReservationReleased() throws Exception {
    ObjectNode reversed = envelope("RefundLedgerReversed");
    ((ObjectNode) reversed.get("payload"))
        .put("reservationId", RESERVATION_ID)
        .put("reversedAt", "2026-01-01T09:00:00.123456+09:00");
    ObjectNode released = envelope("RefundReservationReleased");
    ((ObjectNode) released.get("payload")).put("reservationId", RESERVATION_ID);
    var listener = listener(null);

    listener.receive(record(reversed, PAYMENT_ID), acknowledgment);
    listener.receive(record(released, PAYMENT_ID), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(acceptor, times(2)).acceptRefundEvent(event.capture(), any());
    verify(acknowledgment, times(2)).acknowledge();
    assertThat(event.getAllValues().get(0).payload())
        .isEqualTo(
            new RefundLedgerReversedPayload(
                UUID.fromString(REFUND_ID),
                UUID.fromString(PAYMENT_ID),
                UUID.fromString(RESERVATION_ID),
                OffsetDateTime.parse("2026-01-01T09:00:00.123456+09:00")));
    assertThat(event.getAllValues().get(1).payload())
        .isEqualTo(
            new RefundReservationReleasedPayload(
                UUID.fromString(REFUND_ID),
                UUID.fromString(PAYMENT_ID),
                UUID.fromString(RESERVATION_ID)));
  }

  @ParameterizedTest(name = "reasonCode 길이 {0} → 인계 {1}")
  @CsvSource({"-1, true", "0, false", "1, true", "80, true", "81, false"})
  @DisplayName("사유 코드는 null 또는 1~80자만 인계하고 나머지는 격리한 뒤 offset을 승인한다")
  void limitsReasonCodeLength(int length, boolean accepted) throws Exception {
    ObjectNode json = (ObjectNode) mapper.readTree(RESERVED_JSON);
    ObjectNode payload = (ObjectNode) json.get("payload");
    payload.put("result", "REJECTED").putNull("reservationId");
    if (length < 0) {
      payload.putNull("reasonCode");
    } else {
      payload.put("reasonCode", "A".repeat(length));
    }

    listener(quarantine).receive(record(json, PAYMENT_ID), acknowledgment);

    verify(acknowledgment).acknowledge();
    if (accepted) {
      verify(acceptor).acceptRefundEvent(any(), any());
      verifyNoInteractions(quarantine);
    } else {
      verify(quarantine).capture(any());
      verifyNoInteractions(acceptor);
    }
  }

  @Test
  @DisplayName("Payment가 받지 않는 Settlement 이벤트는 저장 없이 offset만 승인한다")
  void acknowledgesOtherSettlementEventsWithoutAcceptance() throws Exception {
    ObjectNode notice = envelope("SettlementNoticeRequested");
    notice.set("payload", mapper.createObjectNode().put("kind", "SETTLEMENT_CREATED"));

    listener(quarantine).receive(record(notice, "seller-key"), acknowledgment);

    verify(acknowledgment).acknowledge();
    verifyNoInteractions(acceptor, quarantine);
  }

  @Test
  @DisplayName("영속 인계가 실패하면 offset을 승인하지 않는다")
  void doesNotAcknowledgeFailedAcceptance() {
    var failure = new IllegalStateException("database unavailable");
    doThrow(failure).when(acceptor).acceptRefundEvent(any(), any());

    assertThatThrownBy(
            () ->
                listener(null)
                    .receive(
                        new ConsumerRecord<>(
                            TOPIC,
                            0,
                            0L,
                            PAYMENT_ID,
                            RESERVED_JSON.getBytes(StandardCharsets.UTF_8)),
                        acknowledgment))
        .isSameAs(failure);

    verifyNoInteractions(acknowledgment);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "notJson",
        "extraEnvelope",
        "extraPayload",
        "missingNullable",
        "reservedWithoutReservation",
        "enumNumber",
        "unknownEnum",
        "numericReason",
        "unsupportedVersion",
        "wrongKey",
        "otherAggregate",
        "base64Uuid",
        "payloadBase64Uuid",
        "timeWhitespace",
        "otherTopic"
      })
  @DisplayName("계약에 맞지 않는 응답은 업무 호출과 offset 승인 없이 거절한다")
  void rejectsInvalidWireContract(String scenario) throws Exception {
    ObjectNode json = (ObjectNode) mapper.readTree(RESERVED_JSON);
    ObjectNode payload = (ObjectNode) json.get("payload");
    String key = PAYMENT_ID;
    String topic = TOPIC;
    String body = null;
    switch (scenario) {
      case "notJson" -> body = "not-json";
      case "extraEnvelope" -> json.put("sourceService", "settlement");
      case "extraPayload" -> payload.put("unexpected", 1);
      case "missingNullable" -> payload.remove("reasonCode");
      case "reservedWithoutReservation" -> payload.putNull("reservationId");
      case "enumNumber" -> payload.put("result", 0);
      case "unknownEnum" -> payload.put("result", "CONSUMED");
      case "numericReason" -> payload.put("reasonCode", 1);
      case "unsupportedVersion" -> json.put("eventVersion", 2);
      case "wrongKey" -> key = REFUND_ID;
      case "otherAggregate" -> json.put("aggregateId", REFUND_ID);
      case "base64Uuid" -> json.put("eventId", "AAAAAAAAAAAAAAAAAAAABg==");
      case "payloadBase64Uuid" -> payload.put("reservationId", "AAAAAAAAAAAAAAAAAAAAAg==");
      case "timeWhitespace" -> json.put("occurredAt", " 2026-01-01T00:00:00Z ");
      case "otherTopic" -> topic = "commerce-settle.payment.v1";
      default -> throw new IllegalArgumentException(scenario);
    }
    byte[] bytes =
        (body != null ? body : mapper.writeValueAsString(json)).getBytes(StandardCharsets.UTF_8);
    var record = new ConsumerRecord<>(topic, 0, 0L, key, bytes);

    assertThatThrownBy(() -> listener(null).receive(record, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid settlement reply record");

    verifyNoInteractions(acceptor, acknowledgment);
  }

  @Test
  @DisplayName("격리를 켜면 해석할 수 없는 응답의 원문을 격리한 뒤 offset을 승인한다")
  void quarantinesInvalidReply() {
    byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);

    listener(quarantine)
        .receive(new ConsumerRecord<>(TOPIC, 2, 7L, PAYMENT_ID, body), acknowledgment);

    var source = ArgumentCaptor.forClass(QuarantineStore.Source.class);
    var order = inOrder(quarantine, acknowledgment);
    order.verify(quarantine).capture(source.capture());
    order.verify(acknowledgment).acknowledge();
    assertThat(source.getValue().consumer()).isEqualTo("payment.v1");
    assertThat(source.getValue().topic()).isEqualTo(TOPIC);
    assertThat(source.getValue().partition()).isEqualTo(2);
    assertThat(source.getValue().offset()).isEqualTo(7L);
    assertThat(source.getValue().key()).isEqualTo(PAYMENT_ID);
    assertThat(source.getValue().body()).containsExactly(body);
    verifyNoInteractions(acceptor);
  }

  @Test
  @DisplayName("Settlement 응답 수신은 기본으로 등록하지 않는다")
  void keepsListenerDisabledByDefault() {
    context().run(context -> assertThat(context).doesNotHaveBean(SettlementReplyListener.class));
  }

  @Test
  @DisplayName("수신을 활성화해도 영속 인계 구현이 없으면 시작하지 않는다")
  void refusesEnabledListenerWithoutAcceptor() {
    context()
        .withPropertyValues("app.kafka.settlement-replies.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  private SettlementReplyListener listener(QuarantineService quarantine) {
    return new SettlementReplyListener(mapper, acceptor, quarantine, "payment.v1");
  }

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(SettlementReplyListenerConfiguration.class);
  }

  private ObjectNode envelope(String eventType) throws Exception {
    ObjectNode json = (ObjectNode) mapper.readTree(RESERVED_JSON);
    json.put("eventType", eventType);
    json.set(
        "payload",
        mapper.createObjectNode().put("refundId", REFUND_ID).put("paymentId", PAYMENT_ID));
    return json;
  }

  private ConsumerRecord<String, byte[]> record(ObjectNode json, String key) throws Exception {
    return new ConsumerRecord<>(TOPIC, 0, 0L, key, mapper.writeValueAsBytes(json));
  }

  /** Settlement Outbox가 보내는 모양: 원결제 ID가 key·집계이고 nullable 필드도 null로 싣는다. */
  static final String RESERVED_JSON =
      """
      {"eventId":"00000000-0000-0000-0000-0000000000d1","eventType":"RefundReservationResult",
       "eventVersion":1,"occurredAt":"2026-01-01T09:00:00.123456+09:00",
       "aggregateId":"%2$s","aggregateVersion":3,
       "payload":{"refundId":"%1$s","paymentId":"%2$s","result":"RESERVED",
         "reservationId":"%3$s","reasonCode":null}}
      """
          .formatted(REFUND_ID, PAYMENT_ID, RESERVATION_ID);
}
