package com.commercesettle.settlement.infrastructure.messaging;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.application.service.SettlementEventService;
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

/**
 * Payment·Commerce 토픽에서 Settlement가 처리하는 이벤트만 고른다. 처리 여부는 토픽이 아니라 eventType으로 정하고(ADR 0005), 대상이 아닌
 * 종류는 저장 없이 ack한다. 대상 이벤트는 Payment Listener와 같은 엄격한 규칙으로 해석하며, 해석할 수 없으면 ack하지 않고 예외를 던져 컨테이너를 멈춘다.
 * Inbox 저장과 반영이 커밋된 뒤에만 ack한다.
 */
public class SettlementEventListener {
  static final String PAYMENT_TOPIC = "commerce-settle.payment.v1";
  static final String COMMERCE_TOPIC = "commerce-settle.commerce.v1";
  private static final String PAYMENT_CONFIRMED = "PaymentConfirmed";
  private static final String PURCHASE_CONFIRMED = "PurchaseConfirmed";
  private static final long MAX_AMOUNT = 10_000_000L;
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final List<String> PAYMENT_AMOUNTS =
      List.of(
          "grossAmount",
          "couponAmount",
          "totalAmount",
          "mileageAmount",
          "externalAmount",
          "feeAmount",
          "sellerNetAmount",
          "sellerCouponAmount",
          "platformCouponAmount",
          "settlementBaseAmount");

  private final ObjectMapper mapper;
  private final ObjectReader paymentReader;
  private final ObjectReader purchaseReader;
  private final SettlementEventService service;

  public SettlementEventListener(ObjectMapper objectMapper, SettlementEventService service) {
    this.mapper = objectMapper.copy();
    this.mapper.enable(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
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
    this.paymentReader =
        mapper.readerFor(new TypeReference<EventEnvelope<PaymentConfirmedPayload>>() {});
    this.purchaseReader =
        mapper.readerFor(new TypeReference<EventEnvelope<PurchaseConfirmedPayload>>() {});
    this.service = service;
  }

  @KafkaListener(
      id = "settlementEvents",
      idIsGroup = false,
      topics = {PAYMENT_TOPIC, COMMERCE_TOPIC})
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    String eventType = eventType(record);
    Source source = new Source(record.topic(), record.partition(), record.offset(), record.value());
    if (PAYMENT_CONFIRMED.equals(eventType)) {
      service.receivePaymentConfirmed(decodePayment(record), source);
    } else if (PURCHASE_CONFIRMED.equals(eventType)) {
      service.receivePurchaseConfirmed(decodePurchase(record), source);
    }
    acknowledgment.acknowledge();
  }

  private String eventType(ConsumerRecord<String, byte[]> record) {
    try {
      JsonNode type = mapper.readTree(record.value()).path("eventType");
      if (!type.isTextual()) {
        throw new IllegalArgumentException();
      }
      return type.textValue();
    } catch (IOException | RuntimeException exception) {
      // Jackson 예외의 원문 입력을 프레임워크 로그로 전달하지 않는다.
      throw new IllegalArgumentException("Unreadable event record");
    }
  }

  private EventEnvelope<PaymentConfirmedPayload> decodePayment(
      ConsumerRecord<String, byte[]> record) {
    try {
      JsonNode json = envelope(record, PAYMENT_TOPIC);
      JsonNode payload = json.path("payload");
      for (String field :
          List.of("paymentId", "orderId", "buyerId", "sellerId", "paymentCycleId", "productId")) {
        requireUuid(payload.path(field));
      }
      requireTime(payload.path("confirmedAt"));
      for (String field : PAYMENT_AMOUNTS) {
        requireRange(payload.path(field), 0, MAX_AMOUNT);
      }
      requireRange(payload.path("totalAmount"), 1, MAX_AMOUNT);
      requireRange(payload.path("orderVersion"), 0, Long.MAX_VALUE);
      JsonNode policy = payload.path("policyVersion");
      if (!policy.isTextual() || policy.textValue().isEmpty() || policy.textValue().length() > 32) {
        throw new IllegalArgumentException();
      }
      EventEnvelope<PaymentConfirmedPayload> event = paymentReader.readValue(json);
      requireKey(record, event.payload().paymentId().toString());
      return event;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Invalid PaymentConfirmed record");
    }
  }

  private EventEnvelope<PurchaseConfirmedPayload> decodePurchase(
      ConsumerRecord<String, byte[]> record) {
    try {
      JsonNode json = envelope(record, COMMERCE_TOPIC);
      JsonNode payload = json.path("payload");
      requireUuid(payload.path("paymentId"));
      requireUuid(payload.path("orderId"));
      for (String field : List.of("confirmedAt", "deliveredAt", "refundDeadlineAt")) {
        requireTime(payload.path(field));
      }
      requireRange(payload.path("orderVersion"), 0, Long.MAX_VALUE);
      EventEnvelope<PurchaseConfirmedPayload> event = purchaseReader.readValue(json);
      requireKey(record, event.payload().paymentId().toString());
      return event;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Invalid PurchaseConfirmed record");
    }
  }

  /** 생산자 토픽과 Envelope 공통 필드를 검사한다. */
  private JsonNode envelope(ConsumerRecord<String, byte[]> record, String expectedTopic)
      throws IOException {
    if (!expectedTopic.equals(record.topic())) {
      throw new IllegalArgumentException();
    }
    JsonNode json = mapper.readTree(record.value());
    requireUuid(json.path("eventId"));
    requireUuid(json.path("aggregateId"));
    requireTime(json.path("occurredAt"));
    requireRange(json.path("aggregateVersion"), 0, Long.MAX_VALUE);
    if (!json.path("eventVersion").isIntegralNumber() || json.path("eventVersion").asInt() != 1) {
      throw new IllegalArgumentException();
    }
    if (!json.path("payload").isObject()) {
      throw new IllegalArgumentException();
    }
    return json;
  }

  private static void requireKey(ConsumerRecord<String, byte[]> record, String paymentId) {
    if (!paymentId.equals(record.key())) {
      throw new IllegalArgumentException();
    }
  }

  private static void requireUuid(JsonNode node) {
    if (!node.isTextual() || !UUID_PATTERN.matcher(node.textValue()).matches()) {
      throw new IllegalArgumentException();
    }
  }

  private static void requireTime(JsonNode node) {
    if (!node.isTextual()) {
      throw new IllegalArgumentException();
    }
    OffsetDateTime.parse(node.textValue());
  }

  private static void requireRange(JsonNode node, long min, long max) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException();
    }
    long value = node.longValue();
    if (value < min || value > max) {
      throw new IllegalArgumentException();
    }
  }
}
