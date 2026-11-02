package com.commercesettle.settlement.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.service.QuarantineService;
import com.commercesettle.settlement.application.service.SettlementEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class SettlementEventListenerTest {
  private final SettlementEventService service = mock(SettlementEventService.class);
  private final QuarantineService quarantine = mock(QuarantineService.class);
  private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
  private final SettlementEventListener listener =
      new SettlementEventListener(new ObjectMapper(), service, quarantine, "settlement.v1");

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

  private static ConsumerRecord<String, byte[]> invalidRecord() {
    return new ConsumerRecord<>(
        SettlementEventListener.PAYMENT_TOPIC,
        0,
        7,
        "key",
        "{\"eventType\":\"PaymentConfirmed\"}".getBytes(StandardCharsets.UTF_8));
  }
}
