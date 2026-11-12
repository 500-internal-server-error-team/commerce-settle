package com.commercesettle.payment.infrastructure.messaging;

import static com.commercesettle.payment.infrastructure.messaging.StrictEventJson.requireUuid;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.Status;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.QuarantineStore;
import com.commercesettle.payment.application.service.QuarantineService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Commerce 토픽의 결제 요청·미결제 취소 요청·환불 승인·주문 복구 확인을 엄격하게 해석해 Inbox 대기로 넘긴다. 같은 토픽의 다른 eventType(예:
 * Settlement가 소비하는 구매확정)은 저장 없이 ACK한다. eventType을 읽을 수 없거나 처리 대상 종류의 계약에 맞지 않는 원문은 격리한다(격리를 끄면
 * ACK하지 않는다).
 */
public class OrderPaymentRequestedListener {

  private static Instant orderAcceptedAt(ConsumerRecord<String, byte[]> record) {
    var headers = record.headers().headers(BusinessMetrics.ORDER_ACCEPTED_AT_HEADER).iterator();
    if (!headers.hasNext()) return null;
    byte[] value = headers.next().value();
    return headers.hasNext() ? null : BusinessMetrics.parseOrderAcceptedAt(value);
  }

  private static final Set<String> HANDLED_EVENT_TYPES =
      Set.of(
          "OrderPaymentRequested",
          "PaymentCancellationRequested",
          "RefundApproved",
          "RefundOrderRestored");

  private final ObjectReader reader;
  private final ObjectReader cancellationReader;
  private final ObjectReader refundApprovedReader;
  private final ObjectReader refundOrderRestoredReader;
  private final ObjectMapper mapper;
  private final OrderPaymentRequestAcceptor acceptor;
  private final QuarantineService quarantine;
  private final String consumer;

  public OrderPaymentRequestedListener(
      ObjectMapper objectMapper,
      OrderPaymentRequestAcceptor acceptor,
      QuarantineService quarantine,
      String consumer) {
    this.mapper = StrictEventJson.mapper(objectMapper);
    this.reader =
        this.mapper.readerFor(new TypeReference<EventEnvelope<OrderPaymentRequestedPayload>>() {});
    this.cancellationReader =
        this.mapper.readerFor(
            new TypeReference<EventEnvelope<PaymentCancellationRequestedPayload>>() {});
    this.refundApprovedReader =
        this.mapper.readerFor(new TypeReference<EventEnvelope<RefundApprovedPayload>>() {});
    this.refundOrderRestoredReader =
        this.mapper.readerFor(new TypeReference<EventEnvelope<RefundOrderRestoredPayload>>() {});
    this.acceptor = acceptor;
    this.quarantine = quarantine;
    this.consumer = consumer;
  }

  @KafkaListener(
      id = "orderPaymentRequested",
      idIsGroup = false,
      topics = "commerce-settle.commerce.v1")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    EventEnvelope<?> event;
    try {
      event = decode(record);
    } catch (IllegalArgumentException exception) {
      if (quarantine == null) {
        throw exception;
      }
      // 해석 불가 원문도 별도 트랜잭션에 보존하고 커밋 성공 후에만 ACK한다.
      quarantine.capture(
          new QuarantineStore.Source(
              consumer,
              record.topic(),
              record.partition(),
              record.offset(),
              record.key(),
              record.value()));
      acknowledgment.acknowledge();
      return;
    }
    if (event == null) {
      // Payment가 처리하지 않는 종류다. 다른 소비 그룹이 처리하므로 저장·격리 없이 넘긴다.
      acknowledgment.acknowledge();
      return;
    }
    Source source =
        new Source(
            record.topic(),
            record.partition(),
            record.offset(),
            record.value(),
            orderAcceptedAt(record));
    if (event.payload() instanceof RefundApprovedPayload
        || event.payload() instanceof RefundOrderRestoredPayload) {
      acceptor.acceptRefundEvent(event, source);
    } else if (event.payload() instanceof PaymentCancellationRequestedPayload) {
      @SuppressWarnings("unchecked")
      EventEnvelope<PaymentCancellationRequestedPayload> cancellation =
          (EventEnvelope<PaymentCancellationRequestedPayload>) event;
      acceptor.acceptCancellation(cancellation, source);
    } else {
      @SuppressWarnings("unchecked")
      EventEnvelope<OrderPaymentRequestedPayload> request =
          (EventEnvelope<OrderPaymentRequestedPayload>) event;
      acceptor.accept(request, source);
    }
    acknowledgment.acknowledge();
  }

  /** 처리 대상이 아닌 eventType이면 null을 돌려준다. eventType을 읽을 수 없으면 해석 실패다. */
  private EventEnvelope<?> decode(ConsumerRecord<String, byte[]> record) {
    try {
      if (!"commerce-settle.commerce.v1".equals(record.topic())) {
        throw new IllegalArgumentException();
      }
      var json = mapper.readTree(record.value());
      if (json == null || !json.path("eventType").isTextual()) {
        throw new IllegalArgumentException();
      }
      String eventType = json.path("eventType").textValue();
      if (!HANDLED_EVENT_TYPES.contains(eventType)) {
        return null;
      }
      if ("PaymentCancellationRequested".equals(eventType)) {
        return decodeCancellation(json, record.key());
      }
      if ("RefundApproved".equals(eventType)) {
        return decodeRefund(
            json,
            record.key(),
            refundApprovedReader,
            List.of("requestId", "orderId", "paymentId"),
            List.of("acceptedAt", "approvedAt"));
      }
      if ("RefundOrderRestored".equals(eventType)) {
        return decodeRefund(
            json,
            record.key(),
            refundOrderRestoredReader,
            List.of("refundId", "paymentId", "orderId"),
            List.of());
      }
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

  private EventEnvelope<PaymentCancellationRequestedPayload> decodeCancellation(
      JsonNode json, String key) throws IOException {
    if (!json.path("occurredAt").isTextual()) {
      throw new IllegalArgumentException();
    }
    requireUuid(json.path("eventId"));
    requireUuid(json.path("aggregateId"));
    var payload = json.path("payload");
    for (String field : List.of("orderId", "paymentCycleId", "requestId")) {
      requireUuid(payload.path(field));
    }
    OffsetDateTime.parse(json.path("occurredAt").textValue());
    EventEnvelope<PaymentCancellationRequestedPayload> event = cancellationReader.readValue(json);
    if (event.eventVersion() != 1
        || !event.payload().orderId().toString().equals(key)
        || !event.aggregateId().equals(event.payload().orderId())) {
      throw new IllegalArgumentException();
    }
    return event;
  }

  /** 환불 이벤트는 원결제 ID를 Kafka key로 쓴다. 집계는 Commerce 주문이므로 집계 ID는 형식만 검사한다. */
  private EventEnvelope<?> decodeRefund(
      JsonNode json, String key, ObjectReader reader, List<String> uuids, List<String> times)
      throws IOException {
    if (!json.path("occurredAt").isTextual()) {
      throw new IllegalArgumentException();
    }
    requireUuid(json.path("eventId"));
    requireUuid(json.path("aggregateId"));
    var payload = json.path("payload");
    for (String field : uuids) {
      requireUuid(payload.path(field));
    }
    OffsetDateTime.parse(json.path("occurredAt").textValue());
    for (String field : times) {
      if (!payload.path(field).isTextual()) {
        throw new IllegalArgumentException();
      }
      OffsetDateTime.parse(payload.path(field).textValue());
    }
    EventEnvelope<?> event = reader.readValue(json);
    UUID paymentId =
        event.payload() instanceof RefundApprovedPayload approved
            ? approved.paymentId()
            : ((RefundOrderRestoredPayload) event.payload()).paymentId();
    if (event.eventVersion() != 1 || !paymentId.toString().equals(key)) {
      throw new IllegalArgumentException();
    }
    return event;
  }
}
