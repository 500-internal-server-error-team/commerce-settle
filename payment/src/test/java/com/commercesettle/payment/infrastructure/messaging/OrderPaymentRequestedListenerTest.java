package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.infrastructure.config.OrderPaymentRequestedListenerConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
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
    var listener = new OrderPaymentRequestedListener(mapper, acceptor);

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
  @DisplayName("영속 인계가 실패하면 offset을 승인하지 않는다")
  void doesNotAcknowledgeFailedAcceptance() {
    var failure = new IllegalStateException("database unavailable");
    doThrow(failure).when(acceptor).accept(any(), any());
    var listener = new OrderPaymentRequestedListener(mapper, acceptor);

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
    var listener = new OrderPaymentRequestedListener(mapper, acceptor);
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
