package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.infrastructure.config.OrderPaymentRequestedListenerConfiguration;
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
class OrderPaymentRequestedListenerTest {
  private static final String TOPIC = "commerce-settle.commerce.v1";
  private static final String KEY = "00000000-0000-0000-0000-000000000001";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-0000000000e1";
  @Autowired private ObjectMapper mapper;
  @Mock private OrderPaymentRequestAcceptor acceptor;
  @Mock private Acknowledgment acknowledgment;

  @ParameterizedTest
  @ValueSource(strings = {"Z", "+09:00"})
  @DisplayName("Z 또는 KST 입력의 원본 위치와 본문을 인계한 뒤에만 offset을 승인한다")
  @SuppressWarnings("unchecked")
  void acknowledgesAfterDurableAcceptance(String offset) throws Exception {
    var input = EVENT_JSON;
    if (offset.equals("+09:00")) {
      input =
          input.replace("T00:00:00Z", "T09:00:00+09:00").replace("T00:10:00Z", "T09:10:00+09:00");
    }
    var body = input.getBytes(StandardCharsets.UTF_8);
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");

    listener.receive(new ConsumerRecord<>(TOPIC, 2, 73L, KEY, body), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    var source = ArgumentCaptor.forClass(OrderPaymentRequestAcceptor.Source.class);
    var order = inOrder(acceptor, acknowledgment);
    order.verify(acceptor).accept(event.capture(), source.capture());
    order.verify(acknowledgment).acknowledge();
    var actual = mapper.readTree(mapper.writeValueAsBytes(event.getValue()));
    var expected = (ObjectNode) mapper.readTree(EVENT_JSON);
    expected.put("occurredAt", "2026-01-01T09:00:00+09:00");
    ((ObjectNode) expected.get("payload")).put("paymentWaitUntil", "2026-01-01T09:10:00+09:00");
    assertThat(actual).isEqualTo(expected);
    assertThat(source.getValue().topic()).isEqualTo(TOPIC);
    assertThat(source.getValue().partition()).isEqualTo(2);
    assertThat(source.getValue().offset()).isEqualTo(73L);
    assertThat(source.getValue().body()).containsExactly(body);
  }

  @Test
  @DisplayName("같은 토픽의 미결제 취소 요청은 취소 접수로 인계한 뒤 offset을 승인한다")
  @SuppressWarnings("unchecked")
  void acceptsPaymentCancellationRequest() throws Exception {
    var body =
        """
        {"eventId":"00000000-0000-0000-0000-000000000009","eventType":"PaymentCancellationRequested",
         "eventVersion":1,"occurredAt":"2026-01-01T00:05:00Z",
         "aggregateId":"%1$s","aggregateVersion":1,
         "payload":{"orderId":"%1$s","orderVersion":1,
           "paymentCycleId":"00000000-0000-0000-0000-000000000004",
           "requestId":"00000000-0000-0000-0000-000000000008"}}
        """
            .formatted(KEY);
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");

    listener.receive(record(body, KEY), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    var order = inOrder(acceptor, acknowledgment);
    order.verify(acceptor).acceptCancellation(event.capture(), any());
    order.verify(acknowledgment).acknowledge();
    assertThat(event.getValue().payload())
        .isEqualTo(
            new PaymentCancellationRequestedPayload(
                UUID.fromString(KEY),
                1,
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                UUID.fromString("00000000-0000-0000-0000-000000000008")));
  }

  @Test
  @DisplayName("키가 주문 ID와 다른 취소 요청은 접수하지 않는다")
  void rejectsCancellationWithOtherKey() {
    var body =
        """
        {"eventId":"00000000-0000-0000-0000-000000000009","eventType":"PaymentCancellationRequested",
         "eventVersion":1,"occurredAt":"2026-01-01T00:05:00Z",
         "aggregateId":"%1$s","aggregateVersion":1,
         "payload":{"orderId":"%1$s","orderVersion":1,
           "paymentCycleId":"00000000-0000-0000-0000-000000000004",
           "requestId":"00000000-0000-0000-0000-000000000008"}}
        """
            .formatted(KEY);
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");

    assertThatThrownBy(
            () ->
                listener.receive(
                    record(body, "00000000-0000-0000-0000-0000000000ff"), acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(acceptor, acknowledgment);
  }

  @Test
  @DisplayName("같은 토픽의 환불 승인은 원결제 ID를 키로 환불 접수에 인계한 뒤 offset을 승인한다")
  @SuppressWarnings("unchecked")
  void acceptsRefundApproved() throws Exception {
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");

    listener.receive(record(refundApproved(PAYMENT_ID), PAYMENT_ID), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    var order = inOrder(acceptor, acknowledgment);
    order.verify(acceptor).acceptRefundEvent(event.capture(), any());
    order.verify(acknowledgment).acknowledge();
    // 서비스 Jackson 설정은 시각을 Asia/Seoul로 맞추므로 같은 순간인지만 비교한다.
    assertThat(event.getValue().payload())
        .usingRecursiveComparison()
        .withComparatorForType(OffsetDateTime.timeLineOrder(), OffsetDateTime.class)
        .isEqualTo(
            new RefundApprovedPayload(
                UUID.fromString("00000000-0000-0000-0000-000000000008"),
                UUID.fromString(KEY),
                UUID.fromString(PAYMENT_ID),
                OffsetDateTime.parse("2026-01-02T00:00:00Z"),
                OffsetDateTime.parse("2026-01-02T00:01:00Z"),
                3));
  }

  @Test
  @DisplayName("같은 토픽의 주문 복구 확인은 환불 접수에 인계한 뒤 offset을 승인한다")
  @SuppressWarnings("unchecked")
  void acceptsRefundOrderRestored() throws Exception {
    var body =
        """
        {"eventId":"00000000-0000-0000-0000-00000000000b","eventType":"RefundOrderRestored",
         "eventVersion":1,"occurredAt":"2026-01-02T00:05:00Z",
         "aggregateId":"%1$s","aggregateVersion":5,
         "payload":{"refundId":"00000000-0000-0000-0000-00000000000c","paymentId":"%2$s",
           "orderId":"%1$s","orderVersion":5}}
        """
            .formatted(KEY, PAYMENT_ID);
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");

    listener.receive(record(body, PAYMENT_ID), acknowledgment);

    var event = ArgumentCaptor.forClass(EventEnvelope.class);
    var order = inOrder(acceptor, acknowledgment);
    order.verify(acceptor).acceptRefundEvent(event.capture(), any());
    order.verify(acknowledgment).acknowledge();
    assertThat(event.getValue().payload())
        .isEqualTo(
            new RefundOrderRestoredPayload(
                UUID.fromString("00000000-0000-0000-0000-00000000000c"),
                UUID.fromString(PAYMENT_ID),
                UUID.fromString(KEY),
                5));
  }

  @Test
  @DisplayName("키가 원결제 ID와 다르거나 모르는 필드가 있는 환불 승인은 접수하지 않는다")
  void rejectsInvalidRefundApproved() {
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");
    var withExtraField =
        refundApproved(PAYMENT_ID).replace("\"orderVersion\":3", "\"orderVersion\":3,\"x\":1");

    assertThatThrownBy(
            () -> listener.receive(record(refundApproved(PAYMENT_ID), KEY), acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> listener.receive(record(withExtraField, PAYMENT_ID), acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(acceptor, acknowledgment);
  }

  private static String refundApproved(String paymentId) {
    return """
        {"eventId":"00000000-0000-0000-0000-00000000000a","eventType":"RefundApproved",
         "eventVersion":1,"occurredAt":"2026-01-02T00:01:00Z",
         "aggregateId":"%1$s","aggregateVersion":3,
         "payload":{"requestId":"00000000-0000-0000-0000-000000000008","orderId":"%1$s",
           "paymentId":"%2$s","acceptedAt":"2026-01-02T00:00:00Z",
           "approvedAt":"2026-01-02T00:01:00Z","orderVersion":3}}
        """
        .formatted(KEY, paymentId);
  }

  @Test
  @DisplayName("영속 인계가 실패하면 offset을 승인하지 않는다")
  void doesNotAcknowledgeFailedAcceptance() {
    var failure = new IllegalStateException("database unavailable");
    doThrow(failure).when(acceptor).accept(any(), any());
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");

    assertThatThrownBy(() -> listener.receive(record(EVENT_JSON, KEY), acknowledgment))
        .isSameAs(failure);

    verifyNoInteractions(acknowledgment);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "extraEnvelope",
        "extraPayload",
        "missingPrimitive",
        "missingNullable",
        "nullPrimitive",
        "fraction",
        "numericString",
        "numericText",
        "enumNumber",
        "unknownEnum",
        "unsupportedVersion",
        "wrongType",
        "wrongKey",
        "numericTime",
        "missingTime",
        "invalidAmount",
        "enumWhitespace",
        "methodWhitespace",
        "base64Uuid",
        "payloadBase64Uuid",
        "timeWhitespace"
      })
  @DisplayName("계약에 맞지 않는 메시지는 업무 호출과 offset 승인 없이 거절한다")
  void rejectsInvalidWireContract(String scenario) throws Exception {
    ObjectNode json = (ObjectNode) mapper.readTree(EVENT_JSON);
    ObjectNode payload = (ObjectNode) json.get("payload");
    String key = KEY;
    switch (scenario) {
      case "extraEnvelope" -> json.put("sourceService", "commerce");
      case "extraPayload" -> payload.put("unexpected", 1);
      case "missingPrimitive" -> payload.remove("couponAmount");
      case "missingNullable" -> payload.remove("externalMethod");
      case "nullPrimitive" -> payload.putNull("grossAmount");
      case "fraction" -> payload.put("grossAmount", 1.5);
      case "numericString" -> payload.put("grossAmount", "10000");
      case "numericText" -> payload.put("policyVersion", 1);
      case "enumNumber" -> payload.put("status", 0);
      case "unknownEnum" -> payload.put("status", "UNKNOWN");
      case "unsupportedVersion" -> json.put("eventVersion", 2);
      case "wrongType" -> json.put("eventType", "PaymentConfirmed");
      case "wrongKey" -> key = "different-order";
      case "numericTime" -> json.put("occurredAt", 123);
      case "missingTime" -> json.remove("occurredAt");
      case "invalidAmount" -> payload.put("grossAmount", -1);
      case "enumWhitespace" -> payload.put("status", " PAYMENT_PENDING ");
      case "methodWhitespace" -> payload.put("externalMethod", " CREDIT_CARD ");
      case "base64Uuid" -> json.put("eventId", "AAAAAAAAAAAAAAAAAAAABg==");
      case "payloadBase64Uuid" -> payload.put("buyerId", "AAAAAAAAAAAAAAAAAAAAAg==");
      case "timeWhitespace" -> json.put("occurredAt", " 2026-01-01T00:00:00Z ");
      default -> throw new IllegalArgumentException(scenario);
    }
    var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");
    var record = record(mapper.writeValueAsString(json), key);

    assertThatThrownBy(() -> listener.receive(record, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid OrderPaymentRequested record");

    verifyNoInteractions(acceptor, acknowledgment);
  }

  @Test
  @DisplayName("신규 수신 경계는 기본으로 등록하지 않는다")
  void keepsListenerDisabledByDefault() {
    context()
        .run(context -> assertThat(context).doesNotHaveBean(OrderPaymentRequestedListener.class));
  }

  @Test
  @DisplayName("수신을 활성화해도 영속 인계 구현이 없으면 시작하지 않는다")
  void refusesEnabledListenerWithoutAcceptor() {
    context()
        .withPropertyValues("app.kafka.order-payment-requested.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(OrderPaymentRequestedListenerConfiguration.class);
  }

  private ConsumerRecord<String, byte[]> record(String body, String key) {
    return new ConsumerRecord<>(TOPIC, 2, 73L, key, body.getBytes(StandardCharsets.UTF_8));
  }

  static final String EVENT_JSON =
"""
{
  "eventId": "00000000-0000-0000-0000-000000000006",
  "eventType": "OrderPaymentRequested",
  "eventVersion": 1,
  "occurredAt": "2026-01-01T00:00:00Z",
  "aggregateId": "00000000-0000-0000-0000-000000000001",
  "aggregateVersion": 0,
  "payload": {
    "orderId": "00000000-0000-0000-0000-000000000001",
    "buyerId": "00000000-0000-0000-0000-000000000002",
    "sellerId": "00000000-0000-0000-0000-000000000003",
    "paymentCycleId": "00000000-0000-0000-0000-000000000004",
    "productId": "00000000-0000-0000-0000-000000000005",
    "orderVersion": 0,
    "status": "PAYMENT_PENDING",
    "policyVersion": "test-v1",
    "paymentWaitUntil": "2026-01-01T00:10:00Z",
    "externalMethod": null,
    "methodAccountRef": null,
    "grossAmount": 10000,
    "couponAmount": 0,
    "totalAmount": 10000,
    "mileageAmount": 10000,
    "externalAmount": 0,
    "sellerCouponAmount": 0,
    "platformCouponAmount": 0,
    "settlementBaseAmount": 10000
  }
}
""";
}
