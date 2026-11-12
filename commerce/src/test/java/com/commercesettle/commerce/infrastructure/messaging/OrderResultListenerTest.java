package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.commerce.application.service.OrderResultService;
import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.support.Acknowledgment;

class OrderResultListenerTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final OrderResultService results = mock(OrderResultService.class);
  private final PaymentAcceptedService accepted = mock(PaymentAcceptedService.class);
  private final Acknowledgment ack = mock(Acknowledgment.class);
  private final PaymentAcceptedListener listener =
      new PaymentAcceptedListener(mapper, accepted, results);

  @Test
  @DisplayName("결과 영속 인계가 실패하면 ACK하지 않는다")
  void doesNotAcknowledgeFailedHandoff() throws Exception {
    var json = valid();
    org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
        .when(results)
        .accept(any(), any());
    var record =
        new ConsumerRecord<>(
            "commerce-settle.payment.v1",
            0,
            0,
            json.path("aggregateId").asText(),
            mapper.writeValueAsBytes(json));
    assertThatThrownBy(() -> listener.receive(record, ack))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(ack);
  }

  @ParameterizedTest
  @ValueSource(strings = {"extra", "wrongType", "missing", "wrongKey", "fractionalVersion"})
  @DisplayName("스키마·키·타입 오류는 저장과 ACK 전에 거절한다")
  void rejectsMalformedResultsBeforeHandoff(String defect) throws Exception {
    var json = valid();
    String key = json.path("aggregateId").asText();
    switch (defect) {
      case "extra" ->
          ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("payload"))
              .put("unknown", true);
      case "wrongType" ->
          ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("payload"))
              .put("paymentId", true);
      case "missing" ->
          ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("payload"))
              .remove("reasonCode");
      case "wrongKey" -> key = UUID.randomUUID().toString();
      default -> json.put("aggregateVersion", 0.5);
    }
    var record =
        new ConsumerRecord<>(
            "commerce-settle.payment.v1", 0, 0, key, mapper.writeValueAsBytes(json));
    assertThatThrownBy(() -> listener.receive(record, ack))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(results, accepted, ack);
  }

  @Test
  @DisplayName("지원 결과는 영속 인계 뒤 ACK하고 다른 그룹 전용 이벤트는 자기 그룹에서만 건너뛴다")
  void routesResultAndKnownOtherConsumerEvent() throws Exception {
    var json = valid();
    var key = json.path("aggregateId").asText();
    listener.receive(
        new ConsumerRecord<>(
            "commerce-settle.payment.v1", 0, 0, key, mapper.writeValueAsBytes(json)),
        ack);
    verify(results).accept(any(), any());
    verify(ack).acknowledge();
    verifyNoInteractions(accepted);
    var otherAck = mock(Acknowledgment.class);
    json.put("eventType", "RefundFinancialCompleted");
    listener.receive(
        new ConsumerRecord<>(
            "commerce-settle.payment.v1", 0, 1, key, mapper.writeValueAsBytes(json)),
        otherAck);
    verify(otherAck).acknowledge();
  }

  @ParameterizedTest
  @ValueSource(strings = {"RefundAccepted", "RefundCompleted", "RefundFailed"})
  @DisplayName("환불 집계 ID와 결제 Kafka key가 달라도 계약에 맞는 환불 결과를 인계한다")
  void acceptsDistinctRefundAggregate(String type) throws Exception {
    var json = valid();
    var payment = json.path("aggregateId").asText();
    var refund = UUID.randomUUID().toString();
    json.put("eventType", type).put("aggregateId", refund);
    var payload =
        mapper
            .createObjectNode()
            .put("paymentId", payment)
            .put("orderId", UUID.randomUUID().toString())
            .put("refundId", refund);
    if (type.equals("RefundAccepted")) payload.put("requestId", UUID.randomUUID().toString());
    if (type.equals("RefundFailed"))
      payload.put("reasonCode", "DECLINED").put("reservationReleased", true);
    json.set("payload", payload);
    listener.receive(
        new ConsumerRecord<>(
            "commerce-settle.payment.v1", 0, 0, payment, mapper.writeValueAsBytes(json)),
        ack);
    verify(results).accept(any(), any());
    verify(ack).acknowledge();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode valid() {
    var id = UUID.randomUUID().toString();
    var json =
        mapper
            .createObjectNode()
            .put("eventId", UUID.randomUUID().toString())
            .put("eventType", "PaymentDeclined")
            .put("eventVersion", 1)
            .put("occurredAt", "2026-11-02T10:00:00+09:00")
            .put("aggregateId", id)
            .put("aggregateVersion", 0);
    json.set(
        "payload",
        mapper
            .createObjectNode()
            .put("paymentId", id)
            .put("orderId", UUID.randomUUID().toString())
            .put("paymentCycleId", UUID.randomUUID().toString())
            .put("reasonCode", "TEST_DECLINED"));
    return json;
  }
}
