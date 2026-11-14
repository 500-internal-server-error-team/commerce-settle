package com.commercesettle.settlement.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.service.QuarantineService;
import com.commercesettle.settlement.application.service.RefundEventService;
import com.commercesettle.settlement.application.service.SettlementEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class SettlementEventListenerTest {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final SettlementEventService service = mock(SettlementEventService.class);
  private final RefundEventService refunds = mock(RefundEventService.class);
  private final QuarantineService quarantine = mock(QuarantineService.class);
  private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
  private final SettlementEventListener listener =
      new SettlementEventListener(MAPPER, service, refunds, quarantine, "settlement.v1");

  @Test
  @DisplayName("해석 실패 원문을 격리하면 ack한다")
  void acknowledgesAfterQuarantine() {
    listener.receive(invalidRecord(), acknowledgment);

    verify(quarantine).capture(any());
    verify(acknowledgment).acknowledge();
    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("격리 저장에 실패하면 ack하지 않고 예외를 던진다")
  void doesNotAcknowledgeWhenQuarantineFails() {
    when(quarantine.capture(any()))
        .thenThrow(new IllegalStateException("Quarantine persistence failed"));

    assertThatThrownBy(() -> listener.receive(invalidRecord(), acknowledgment))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(acknowledgment);
  }

  @Test
  @DisplayName("처리기와 같은 항목을 동시에 바꿔 버전이 충돌하면 새 트랜잭션으로 한 번 다시 판정하고 ack한다")
  void retriesPurchaseOnceAfterOptimisticLockConflict() throws Exception {
    doThrow(new ObjectOptimisticLockingFailureException("SettlementItem", UUID.randomUUID()))
        .doNothing()
        .when(service)
        .receivePurchaseConfirmed(any(), any());

    listener.receive(purchaseRecord(), acknowledgment);

    verify(service, times(2)).receivePurchaseConfirmed(any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("다시 판정해도 충돌하면 ack하지 않고 예외를 던진다")
  void stopsWhenConflictRepeats() throws Exception {
    doThrow(new ObjectOptimisticLockingFailureException("SettlementItem", UUID.randomUUID()))
        .when(service)
        .receivePurchaseConfirmed(any(), any());

    assertThatThrownBy(() -> listener.receive(purchaseRecord(), acknowledgment))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(service, times(2)).receivePurchaseConfirmed(any(), any());
    verifyNoInteractions(acknowledgment);
  }

  @Test
  @DisplayName("기존 여섯 필드 구매확정은 선택 시연 필드가 없어도 읽되 기존 필수값 누락은 거절한다")
  void readsLegacyPurchaseWithoutWeakeningRequiredFields() throws Exception {
    var original = purchaseRecord();
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(original.value());
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) body.path("payload");
    payload.remove(java.util.List.of("demoRunId", "demoRefundSeconds"));
    listener.receive(
        new ConsumerRecord<>(
            original.topic(), 0, 3, original.key(), MAPPER.writeValueAsBytes(body)),
        acknowledgment);
    verify(service).receivePurchaseConfirmed(any(), any());
    verifyNoInteractions(quarantine);
    org.mockito.Mockito.clearInvocations(service, acknowledgment);
    payload.remove("confirmedAt");
    listener.receive(
        new ConsumerRecord<>(
            original.topic(), 0, 4, original.key(), MAPPER.writeValueAsBytes(body)),
        acknowledgment);
    verifyNoInteractions(service);
    verify(quarantine).capture(any());
  }

  private static ConsumerRecord<String, byte[]> purchaseRecord() throws Exception {
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OffsetDateTime deliveredAt = OffsetDateTime.parse("2026-10-10T12:00:00+09:00");
    byte[] body =
        MAPPER.writeValueAsBytes(
            new EventEnvelope<>(
                UUID.randomUUID(),
                "PurchaseConfirmed",
                1,
                OffsetDateTime.parse("2026-10-17T12:10:00+09:00"),
                orderId,
                5,
                new PurchaseConfirmedPayload(
                    paymentId,
                    orderId,
                    4,
                    deliveredAt.plusHours(168).plusMinutes(5),
                    deliveredAt,
                    deliveredAt.plusHours(168))));
    return new ConsumerRecord<>(
        SettlementEventListener.COMMERCE_TOPIC, 0, 3, paymentId.toString(), body);
  }

  private static ConsumerRecord<String, byte[]> invalidRecord() {
    return new ConsumerRecord<>(
        SettlementEventListener.PAYMENT_TOPIC,
        0,
        7,
        "key",
        "{\"eventType\":\"PaymentConfirmed\"}".getBytes(StandardCharsets.UTF_8));
  }
}
