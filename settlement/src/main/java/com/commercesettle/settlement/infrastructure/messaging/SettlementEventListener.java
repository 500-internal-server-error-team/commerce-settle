package com.commercesettle.settlement.infrastructure.messaging;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.application.port.QuarantineStore;
import com.commercesettle.settlement.application.service.QuarantineService;
import com.commercesettle.settlement.application.service.RefundEventService;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Payment·Commerce 토픽에서 Settlement가 처리하는 이벤트만 고른다. 처리 여부는 토픽이 아니라 eventType으로 정하고(ADR 0005), 대상이 아닌
 * 종류는 저장 없이 ack한다. 대상은 결제확정·구매확정과 환불 SAGA 요청이다. 대상 이벤트는 Payment Listener와 같은 엄격한 규칙으로 해석한다. 해석할 수
 * 없으면 격리가 켜져 있을 때 원문과 DLT 작업을 저장한 뒤 ack하고, 꺼져 있으면 ack하지 않고 예외를 던져 컨테이너를 멈춘다. 반영 중 오류는 격리하지 않는다.
 * Inbox 저장과 반영이 커밋된 뒤에만 ack한다.
 */
public class SettlementEventListener {
  static final String PAYMENT_TOPIC = "commerce-settle.payment.v1";
  static final String COMMERCE_TOPIC = "commerce-settle.commerce.v1";
  private static final String PAYMENT_CONFIRMED = "PaymentConfirmed";
  private static final String PURCHASE_CONFIRMED = "PurchaseConfirmed";
  private static final String REFUND_RESERVATION_REQUESTED = "RefundReservationRequested";
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
  private final ObjectReader refundReservationReader;
  private final SettlementEventService service;
  private final RefundEventService refunds;
  private final QuarantineService quarantine;
  private final String consumer;

  /** {@code quarantine}이 null이면 해석 실패에 컨테이너를 멈춘다. */
  public SettlementEventListener(
      ObjectMapper objectMapper,
      SettlementEventService service,
      RefundEventService refunds,
      QuarantineService quarantine,
      String consumer) {
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
    this.refundReservationReader =
        mapper.readerFor(new TypeReference<EventEnvelope<RefundReservationRequestedPayload>>() {});
    this.service = service;
    this.refunds = refunds;
    this.quarantine = quarantine;
    this.consumer = consumer;
  }

  @KafkaListener(
      id = "settlementEvents",
      idIsGroup = false,
      topics = {PAYMENT_TOPIC, COMMERCE_TOPIC})
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    Source source = new Source(record.topic(), record.partition(), record.offset(), record.value());
    try {
      String eventType = eventType(record);
      if (PAYMENT_CONFIRMED.equals(eventType)) {
        service.receivePaymentConfirmed(decodePayment(record), source);
      } else if (PURCHASE_CONFIRMED.equals(eventType)) {
        receivePurchase(decodePurchase(record), source);
      } else if (REFUND_RESERVATION_REQUESTED.equals(eventType)) {
        refunds.receiveReservationRequested(decodeRefundReservation(record), source);
      }
    } catch (InvalidRecordException exception) {
      if (quarantine == null) {
        throw exception;
      }
      // 원문과 DLT 작업을 별도 트랜잭션에 저장한다. 저장에 실패하면 예외가 나가 ack하지 않는다.
      quarantine.capture(
          new QuarantineStore.Source(
              consumer,
              record.topic(),
              record.partition(),
              record.offset(),
              record.key(),
              record.value()));
    }
    acknowledgment.acknowledge();
  }

  private void receivePurchase(EventEnvelope<PurchaseConfirmedPayload> event, Source source) {
    try {
      service.receivePurchaseConfirmed(event, source);
    } catch (OptimisticLockingFailureException exception) {
      // 대기 처리기가 같은 항목을 먼저 바꿨다. 이번 수신은 Inbox까지 롤백됐으므로 새 트랜잭션으로 한 번 다시 판정한다.
      service.receivePurchaseConfirmed(event, source);
    }
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
      throw new InvalidRecordException("Unreadable event record");
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
      throw new InvalidRecordException("Invalid PaymentConfirmed record");
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
      throw new InvalidRecordException("Invalid PurchaseConfirmed record");
    }
  }

  private EventEnvelope<RefundReservationRequestedPayload> decodeRefundReservation(
      ConsumerRecord<String, byte[]> record) {
    try {
      JsonNode json = envelope(record, PAYMENT_TOPIC);
      JsonNode payload = json.path("payload");
      requireUuid(payload.path("refundId"));
      requireUuid(payload.path("paymentId"));
      requireRange(payload.path("netAmount"), 0, MAX_AMOUNT);
      EventEnvelope<RefundReservationRequestedPayload> event =
          refundReservationReader.readValue(json);
      requireKey(record, event.payload().paymentId().toString());
      return event;
    } catch (IOException | RuntimeException exception) {
      throw new InvalidRecordException("Invalid RefundReservationRequested record");
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

  /** 해석 단계의 실패만 나타낸다. 반영 중 예외와 구분해 격리 대상을 정한다. */
  static final class InvalidRecordException extends IllegalArgumentException {
    InvalidRecordException(String message) {
      super(message);
    }
  }
}
