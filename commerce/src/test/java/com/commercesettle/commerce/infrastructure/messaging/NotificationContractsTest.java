package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.application.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.support.Acknowledgment;

class NotificationContractsTest {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final NotificationContracts contracts = new NotificationContracts(mapper);

  @Test
  @DisplayName("notice의 키는 paymentId·batchId로 검사하고 발신 aggregateId와 분리한다")
  void validatesNoticeKeysIndependentlyOfAggregate() throws Exception {
    var payment = payment();
    var p = record("PaymentNoticeRequested", payment, payment.path("paymentId").asText());
    assertThat(contracts.decode(p).payload()).isEqualTo(payment);
    var settlement = settlement();
    var s = record("SettlementNoticeRequested", settlement, settlement.path("batchId").asText());
    assertThat(contracts.decode(s).payload()).isEqualTo(settlement);
    String upper = "12345678-ABCD-4ABC-8DEF-123456789ABC";
    var upperPayment = payment.deepCopy().put("paymentId", upper);
    assertThat(contracts.decode(record("PaymentNoticeRequested", upperPayment, upper)).payload())
        .isEqualTo(upperPayment);
    var upperSettlement = settlement.deepCopy().put("batchId", upper).put("businessRef", upper);
    assertThat(
            contracts.decode(record("SettlementNoticeRequested", upperSettlement, upper)).payload())
        .isEqualTo(upperSettlement);
    assertThatThrownBy(
            () ->
                contracts.decode(
                    record("PaymentNoticeRequested", payment, UUID.randomUUID().toString())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                contracts.decode(
                    record("SettlementNoticeRequested", settlement, UUID.randomUUID().toString())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("다른 금융 이벤트는 자신의 키 규칙을 가정하지 않고 알림 그룹에서 건너뛴다")
  void skipsOtherFinancialEventsWithoutAssumingTheirKeys() throws Exception {
    assertThat(
            contracts.decode(
                record(
                    "PaymentConfirmed", mapper.createObjectNode(), UUID.randomUUID().toString())))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"businessRef", "walletVersion", "sum", "extra", "string", "uuid"})
  @DisplayName("거래 참조·잔액 버전·금액 보존·허용 필드·정수·UUID 위반을 거절한다")
  void rejectsInvalidPaymentNotice(String invalid) throws Exception {
    var p = payment();
    switch (invalid) {
      case "businessRef" -> p.put("businessRef", UUID.randomUUID().toString());
      case "walletVersion" -> p.putNull("walletVersion");
      case "sum" -> p.put("externalAmount", 10000);
      case "extra" -> p.put("phone", "000-0000-0000");
      case "string" -> p.put("mileageAmount", "1000");
      case "uuid" -> p.put("buyerId", "1-1-1-1-1");
      default -> throw new IllegalArgumentException();
    }
    assertThatThrownBy(
            () ->
                contracts.decode(record("PaymentNoticeRequested", p, p.path("paymentId").asText())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("영속 인계나 원문 격리 저장이 실패하면 ACK하지 않는다")
  void leavesOffsetUnacknowledgedWhenDurableHandoffFails() throws Exception {
    var service = mock(NotificationService.class);
    var ack = mock(Acknowledgment.class);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
        .when(service)
        .accept(any(), any());
    var listener = new NotificationListener(mapper, service);
    var p = payment();
    assertThatThrownBy(
            () ->
                listener.receive(
                    record("PaymentNoticeRequested", p, p.path("paymentId").asText()), ack))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    verifyNoInteractions(ack);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
        .when(service)
        .quarantine(any(), any());
    assertThatThrownBy(
            () ->
                listener.receive(
                    new ConsumerRecord<>(
                        "commerce-settle.payment.v1", 0, 1, null, new byte[] {(byte) 255}),
                    ack))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    verifyNoInteractions(ack);
  }

  @Test
  @DisplayName("해석 불가 원문의 bytes·키·위치를 인계한 뒤 ACK한다")
  void preservesMalformedRecordBeforeAcknowledgment() {
    var service = mock(NotificationService.class);
    var ack = mock(Acknowledgment.class);
    byte[] raw = {(byte) 255, 0, 17};
    new NotificationListener(mapper, service)
        .receive(new ConsumerRecord<>("commerce-settle.payment.v1", 2, 19, "raw-key", raw), ack);
    var source = org.mockito.ArgumentCaptor.forClass(NotificationStore.Source.class);
    var order = org.mockito.Mockito.inOrder(service, ack);
    order.verify(service).quarantine(source.capture(), eq("NOTIFICATION_NOTICE_INVALID"));
    order.verify(ack).acknowledge();
    assertThat(source.getValue().body()).isEqualTo(raw);
    assertThat(source.getValue().key()).isEqualTo("raw-key");
    assertThat(source.getValue().partition()).isEqualTo(2);
    assertThat(source.getValue().offset()).isEqualTo(19);
  }

  private ConsumerRecord<String, byte[]> record(String type, ObjectNode p, String key)
      throws Exception {
    var event =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            type,
            1,
            OffsetDateTime.now(ZoneOffset.UTC),
            UUID.randomUUID(),
            2,
            p);
    return new ConsumerRecord<>(
        type.startsWith("Settlement")
            ? "commerce-settle.settlement.v1"
            : "commerce-settle.payment.v1",
        0,
        0,
        key,
        mapper.writeValueAsBytes(event));
  }

  private ObjectNode payment() throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    return (ObjectNode)
        canonical
            .path("components")
            .path("schemas")
            .path("PaymentNoticeRequestedPayload")
            .path("examples")
            .get(0)
            .deepCopy();
  }

  private ObjectNode settlement() {
    UUID batch = UUID.randomUUID();
    var p =
        mapper
            .createObjectNode()
            .put("kind", "SETTLEMENT_READY")
            .put("businessRef", batch.toString())
            .put("batchId", batch.toString())
            .put("sellerId", UUID.randomUUID().toString())
            .put("cutoffAt", "2026-11-02T00:00:00+09:00")
            .put("amount", 0)
            .put("batchDate", "2026-11-01")
            .put("policyVersion", "test-v1");
    return p.putNull("payoutId").putNull("succeededAt").putNull("reasonCode");
  }
}
