package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.Status;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
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

public class OrderPaymentRequestedListener {

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final ObjectReader reader;
  private final ObjectMapper mapper;
  private final OrderPaymentRequestAcceptor acceptor;

  public OrderPaymentRequestedListener(
      ObjectMapper objectMapper, OrderPaymentRequestAcceptor acceptor) {
    this.mapper = objectMapper.copy();
    this.mapper.enable(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    this.mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    this.mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    this.reader =
        this.mapper.readerFor(new TypeReference<EventEnvelope<OrderPaymentRequestedPayload>>() {});
    this.acceptor = acceptor;
  }

  @KafkaListener(
      id = "orderPaymentRequested",
      idIsGroup = false,
      topics = "commerce-settle.commerce.v1")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    var event = decode(record);
    acceptor.accept(
        event, new Source(record.topic(), record.partition(), record.offset(), record.value()));
    acknowledgment.acknowledge();
  }

  private EventEnvelope<OrderPaymentRequestedPayload> decode(
      ConsumerRecord<String, byte[]> record) {
    try {
      if (!"commerce-settle.commerce.v1".equals(record.topic())) {
        throw new IllegalArgumentException();
      }
      var json = mapper.readTree(record.value());
      if (json == null
          || !json.path("occurredAt").isTextual()
          || !json.path("payload").path("paymentWaitUntil").isTextual()) {
        throw new IllegalArgumentException();
      }
      requireUuid(json.path("eventId"));
      requireUuid(json.path("aggregateId"));
      var payload = json.path("payload");
      for (String field :
          List.of("orderId", "buyerId", "sellerId", "paymentCycleId", "productId")) {
        requireUuid(payload.path(field));
      }
      Status.valueOf(payload.path("status").textValue());
      if (!payload.path("externalMethod").isNull()) {
        ExternalMethod.valueOf(payload.path("externalMethod").textValue());
      }
      OffsetDateTime.parse(json.path("occurredAt").textValue());
      OffsetDateTime.parse(payload.path("paymentWaitUntil").textValue());
      EventEnvelope<OrderPaymentRequestedPayload> event = reader.readValue(json);
      if (!"OrderPaymentRequested".equals(event.eventType())
          || event.eventVersion() != 1
          || !event.payload().orderId().toString().equals(record.key())) {
        throw new IllegalArgumentException();
      }
      return event;
    } catch (IOException | RuntimeException exception) {
      // Jackson 예외의 원문 입력을 프레임워크 로그로 전달하지 않는다.
      throw new IllegalArgumentException("Invalid OrderPaymentRequested record");
    }
  }

  private static void requireUuid(JsonNode node) {
    if (!node.isTextual() || !UUID_PATTERN.matcher(node.textValue()).matches()) {
      throw new IllegalArgumentException();
    }
  }
}
