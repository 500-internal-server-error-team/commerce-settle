package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source;
import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.support.Acknowledgment;

class PaymentAcceptedListenerTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final PaymentAcceptedService service = mock(PaymentAcceptedService.class);
  private final Acknowledgment ack = mock(Acknowledgment.class);
  private final PaymentAcceptedListener listener =
      new PaymentAcceptedListener(
          mapper,
          service,
          mock(com.commercesettle.commerce.application.service.OrderResultService.class));
  private final UUID payment = UUID.randomUUID();

  @Test
  @DisplayName("영속 인계 서비스 반환 뒤에만 ACK한다")
  void acknowledgesAfterDurableHandoff() throws Exception {
    listener.receive(record(body()), ack);

    var order = inOrder(service, ack);
    order.verify(service).accept(any(), any());
    order.verify(ack).acknowledge();
  }

  @Test
  @DisplayName("DB 인계 실패는 ACK하지 않는다")
  void doesNotAcknowledgeStorageFailure() throws Exception {
    doThrow(new IllegalStateException("injected failure")).when(service).accept(any(), any());

    assertThatThrownBy(() -> listener.receive(record(body()), ack))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(ack);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "unknown",
        "uuid",
        "key",
        "aggregate",
        "version",
        "fraction",
        "string",
        "eventType",
        "time"
      })
  @DisplayName("계약이 맞지 않는 메시지는 원문 노출·영속 인계·ACK 없이 거절한다")
  void rejectsInvalidRecordWithoutHandoff(String kind) throws Exception {
    var body = body();
    switch (kind) {
      case "missing" -> ((ObjectNode) body.path("payload")).remove("orderVersion");
      case "unknown" -> body.put("unexpected", "do-not-log-this-value");
      case "uuid" -> body.put("eventId", "invalid-uuid");
      case "aggregate" -> body.put("aggregateId", UUID.randomUUID().toString());
      case "version" -> body.put("eventVersion", 2);
      case "fraction" -> ((ObjectNode) body.path("payload")).put("orderVersion", 0.5);
      case "string" -> ((ObjectNode) body.path("payload")).put("orderVersion", "0");
      case "eventType" -> body.put("eventType", "PaymentConfirmed");
      case "time" -> body.put("occurredAt", "2026-11-02T10:00:00");
      default -> {}
    }
    var record =
        "key".equals(kind)
            ? new ConsumerRecord<String, byte[]>(
                "commerce-settle.payment.v1", 0, 0, "wrong-key", mapper.writeValueAsBytes(body))
            : record(body);

    assertThatThrownBy(() -> listener.receive(record, ack))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            kind.equals("eventType")
                ? "Invalid order result record"
                : "Invalid PaymentAccepted record");
    verifyNoInteractions(service, ack);
  }

  @Test
  @DisplayName("원문 입력·반환 배열 변경이 보존할 출처 bytes를 바꾸지 않는다")
  void protectsSourceBytes() {
    byte[] body = {1, 2};
    var source = new Source("commerce-settle.payment.v1", 0, 0, body);
    body[0] = 9;
    source.body()[1] = 9;
    assertThat(source.body()).containsExactly((byte) 1, (byte) 2);
  }

  private ConsumerRecord<String, byte[]> record(ObjectNode body) throws Exception {
    return new ConsumerRecord<>(
        "commerce-settle.payment.v1", 0, 0, payment.toString(), mapper.writeValueAsBytes(body));
  }

  private ObjectNode body() {
    var body =
        mapper
            .createObjectNode()
            .put("eventId", UUID.randomUUID().toString())
            .put("eventType", "PaymentAccepted")
            .put("eventVersion", 1)
            .put("occurredAt", "2026-11-02T10:00:00+09:00")
            .put("aggregateId", payment.toString())
            .put("aggregateVersion", 0);
    body.putObject("payload")
        .put("paymentId", payment.toString())
        .put("orderId", UUID.randomUUID().toString())
        .put("orderVersion", 0)
        .put("paymentCycleId", UUID.randomUUID().toString());
    return body;
  }
}
