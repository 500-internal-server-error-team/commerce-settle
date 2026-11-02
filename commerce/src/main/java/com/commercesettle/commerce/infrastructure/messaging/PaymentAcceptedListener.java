package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source;
import com.commercesettle.commerce.application.service.OrderResultService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
  private final OrderResultService results;
  private final OrderResultContracts contracts;

  public PaymentAcceptedListener(
      ObjectMapper mapper, PaymentAcceptedService service, OrderResultService results) {
    this.mapper =
        mapper
            .copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
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
    this.results = java.util.Objects.requireNonNull(results);
    this.contracts = new OrderResultContracts(mapper);
  }

  @KafkaListener(id = "paymentAccepted", idIsGroup = false, topics = "commerce-settle.payment.v1")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    var json = read(record);
    String type = json.path("eventType").asText();
    if (OrderResultContracts.TYPES.contains(type)) {
      var event = decodeResult(record, json);
      results.accept(
          event, new Source(record.topic(), record.partition(), record.offset(), record.value()));
      acknowledgment.acknowledge();
      return;
    }
    if (results != null
        && Set.of(
                "RefundReservationRequested",
                "RefundFinancialCompleted",
                "RefundReleaseRequested",
                "PaymentNoticeRequested")
            .contains(type)) {
      // 같은 생산자 토픽의 다른 소비자용 이벤트는 자기 그룹에서만 건너뛴다.
      validateEnvelope(record, json);
      acknowledgment.acknowledge();
      return;
    }
    var event = decode(record, json);
    service.accept(
        event, new Source(record.topic(), record.partition(), record.offset(), record.value()));
    acknowledgment.acknowledge();
  }

  private EventEnvelope<PaymentAcceptedPayload> decode(
      ConsumerRecord<String, byte[]> record, JsonNode json) {
    try {
      if (!"commerce-settle.payment.v1".equals(record.topic()))
        throw new IllegalArgumentException();
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

  private JsonNode read(ConsumerRecord<String, byte[]> record) {
    try {
      var json = mapper.readTree(record.value());
      if (json == null || !json.isObject()) throw new IllegalArgumentException();
      return json;
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("Invalid payment record");
    }
  }

  private EventEnvelope<JsonNode> decodeResult(
      ConsumerRecord<String, byte[]> record, JsonNode json) {
    try {
      validateEnvelope(record, json);
      contracts.validate(json.required("eventType").textValue(), json.required("payload"));
      for (var field : json.required("payload").properties()) {
        if (field.getKey().endsWith("Id")) requireUuid(field.getValue());
        if (field.getKey().endsWith("At")) OffsetDateTime.parse(field.getValue().textValue());
      }
      return mapper.readerFor(new TypeReference<EventEnvelope<JsonNode>>() {}).readValue(json);
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("Invalid order result record");
    }
  }

  private static void validateEnvelope(ConsumerRecord<String, byte[]> record, JsonNode json) {
    var fields = new HashSet<String>();
    json.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(
            Set.of(
                "eventId",
                "eventType",
                "eventVersion",
                "occurredAt",
                "aggregateId",
                "aggregateVersion",
                "payload"))
        || !"commerce-settle.payment.v1".equals(record.topic())
        || !json.required("eventVersion").isIntegralNumber()
        || !json.required("eventVersion").canConvertToInt()
        || json.required("eventVersion").intValue() != 1
        || !json.required("aggregateVersion").isIntegralNumber()
        || !json.required("aggregateVersion").canConvertToLong()
        || json.required("aggregateVersion").longValue() < 0
        || !json.required("payload").isObject())
      throw new IllegalArgumentException("Invalid payment envelope");
    requireUuid(json.required("eventId"));
    requireUuid(json.required("aggregateId"));
    if (!json.required("payload").required("paymentId").textValue().equals(record.key()))
      throw new IllegalArgumentException("Invalid payment key");
    OffsetDateTime.parse(json.required("occurredAt").textValue());
  }

  private static void requireUuid(JsonNode node) {
    if (!node.isTextual() || !UUID_PATTERN.matcher(node.textValue()).matches()) {
      throw new IllegalArgumentException();
    }
  }
}
