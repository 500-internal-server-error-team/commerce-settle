package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source;
import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** 원문 검증·영속 인계가 성공한 뒤에만 ACK한다. 업무 반영은 별도 처리기가 맡는다. */
public class PaymentAcceptedListener {
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final ObjectMapper mapper;
  private final ObjectReader reader;
  private final PaymentAcceptedService service;

  public PaymentAcceptedListener(ObjectMapper mapper, PaymentAcceptedService service) {
    this.mapper =
        mapper
            .copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    this.mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    this.reader =
        this.mapper.readerFor(new TypeReference<EventEnvelope<PaymentAcceptedPayload>>() {});
    this.service = service;
  }

  @KafkaListener(id = "paymentAccepted", idIsGroup = false, topics = "commerce-settle.payment.v1")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    var event = decode(record);
    service.accept(
        event, new Source(record.topic(), record.partition(), record.offset(), record.value()));
    acknowledgment.acknowledge();
  }

  private EventEnvelope<PaymentAcceptedPayload> decode(ConsumerRecord<String, byte[]> record) {
    try {
      if (!"commerce-settle.payment.v1".equals(record.topic()))
        throw new IllegalArgumentException();
      var json = mapper.readTree(record.value());
      if (json == null || !json.path("occurredAt").isTextual())
        throw new IllegalArgumentException();
      for (String field : List.of("eventId", "aggregateId")) requireUuid(json.path(field));
      for (String field : List.of("paymentId", "orderId", "paymentCycleId")) {
        requireUuid(json.path("payload").path(field));
      }
      OffsetDateTime.parse(json.path("occurredAt").textValue());
      EventEnvelope<PaymentAcceptedPayload> event = reader.readValue(json);
      if (!"PaymentAccepted".equals(event.eventType())
          || event.eventVersion() != 1
          || !event.aggregateId().equals(event.payload().paymentId())
          || !event.payload().paymentId().toString().equals(record.key())) {
        throw new IllegalArgumentException();
      }
      return event;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Invalid PaymentAccepted record");
    }
  }

  private static void requireUuid(JsonNode node) {
    if (!node.isTextual() || !UUID_PATTERN.matcher(node.textValue()).matches()) {
      throw new IllegalArgumentException();
    }
  }
}
