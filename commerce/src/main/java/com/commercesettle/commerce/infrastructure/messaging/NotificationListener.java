package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.port.NotificationStore.Source;
import com.commercesettle.commerce.application.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class NotificationListener {
  private final NotificationContracts contracts;
  private final NotificationService service;

  public NotificationListener(ObjectMapper mapper, NotificationService service) {
    contracts = new NotificationContracts(mapper);
    this.service = service;
  }

  @KafkaListener(
      id = "transactionNotifications",
      idIsGroup = false,
      groupId = "commerce-notifications.v1",
      topics = {"commerce-settle.payment.v1", "commerce-settle.settlement.v1"})
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    var source =
        new Source(
            record.topic(), record.partition(), record.offset(), record.key(), record.value());
    try {
      var event = contracts.decode(record);
      if (event != null) service.accept(event, source);
    } catch (IllegalArgumentException
        | com.commercesettle.commerce.domain.model.Notification.ContentConflictException e) {
      service.quarantine(source, "NOTIFICATION_NOTICE_INVALID");
    }
    // DB 인계/격리가 실패하면 여기까지 오지 않는다. 금융 그룹의 offset은 바꾸지 않는다.
    ack.acknowledge();
  }
}
