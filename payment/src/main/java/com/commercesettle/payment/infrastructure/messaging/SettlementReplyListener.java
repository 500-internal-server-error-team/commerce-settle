package com.commercesettle.payment.infrastructure.messaging;

import static com.commercesettle.payment.infrastructure.messaging.StrictEventJson.requireUuid;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.QuarantineStore;
import com.commercesettle.payment.application.service.QuarantineService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Settlement 토픽의 환불 응답(RefundReservationResult·RefundLedgerReversed·RefundReservationReleased)을
 * 엄격하게 해석해 Inbox 대기로 넘긴다. 반영은 결제 요청 처리기가 Commerce 환불 이벤트와 같은 선점·재시도 규칙으로 한다.
 *
 * <p>같은 토픽의 다른 종류(Settlement 알림 등)는 Payment가 처리할 일이 없으므로 저장 없이 ACK한다. 응답 종류인데 계약에 맞지 않거나 JSON으로 읽을
 * 수 없는 원문은 격리한다(격리를 끄면 ACK하지 않는다).
 */
public class SettlementReplyListener {
  static final String TOPIC = "commerce-settle.settlement.v1";

  private final ObjectMapper mapper;
  private final Map<String, Reply> replies;
  private final OrderPaymentRequestAcceptor acceptor;
  private final QuarantineService quarantine;
  private final String consumer;

  public SettlementReplyListener(
      ObjectMapper objectMapper,
      OrderPaymentRequestAcceptor acceptor,
      QuarantineService quarantine,
      String consumer) {
    this.mapper = StrictEventJson.mapper(objectMapper);
    this.replies =
        Map.of(
            "RefundReservationResult",
            new Reply(
                mapper.readerFor(
                    new TypeReference<EventEnvelope<RefundReservationResultPayload>>() {}),
                List.of("refundId", "paymentId"),
                List.of("reservationId"),
                List.of()),
            "RefundLedgerReversed",
            new Reply(
                mapper.readerFor(
                    new TypeReference<EventEnvelope<RefundLedgerReversedPayload>>() {}),
                List.of("refundId", "paymentId", "reservationId"),
                List.of(),
                List.of("reversedAt")),
            "RefundReservationReleased",
            new Reply(
                mapper.readerFor(
                    new TypeReference<EventEnvelope<RefundReservationReleasedPayload>>() {}),
                List.of("refundId", "paymentId", "reservationId"),
                List.of(),
                List.of()));
    this.acceptor = acceptor;
    this.quarantine = quarantine;
    this.consumer = consumer;
  }

  @KafkaListener(id = "settlementReplies", idIsGroup = false, topics = TOPIC)
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
    if (event != null) {
      acceptor.acceptRefundEvent(
          event, new Source(record.topic(), record.partition(), record.offset(), record.value()));
    }
    acknowledgment.acknowledge();
  }

  /** 환불 응답이면 해석한 이벤트를, Payment가 받지 않는 종류면 null을 돌려준다. */
  private EventEnvelope<?> decode(ConsumerRecord<String, byte[]> record) {
    try {
      if (!TOPIC.equals(record.topic())) {
        throw new IllegalArgumentException();
      }
      JsonNode json = mapper.readTree(record.value());
      if (json == null || !json.isObject() || !json.path("eventType").isTextual()) {
        throw new IllegalArgumentException();
      }
      Reply reply = replies.get(json.path("eventType").textValue());
      if (reply == null) {
        return null;
      }
      if (!json.path("occurredAt").isTextual()) {
        throw new IllegalArgumentException();
      }
      requireUuid(json.path("eventId"));
      requireUuid(json.path("aggregateId"));
      OffsetDateTime.parse(json.path("occurredAt").textValue());
      JsonNode payload = json.path("payload");
      for (String field : reply.uuids()) {
        requireUuid(payload.path(field));
      }
      for (String field : reply.nullableUuids()) {
        if (!payload.path(field).isNull()) {
          requireUuid(payload.path(field));
        }
      }
      for (String field : reply.times()) {
        if (!payload.path(field).isTextual()) {
          throw new IllegalArgumentException();
        }
        OffsetDateTime.parse(payload.path(field).textValue());
      }
      EventEnvelope<?> event = reply.reader().readValue(json);
      UUID paymentId = paymentId(event.payload());
      // Settlement는 환불 응답을 원결제 ID를 key·집계로 보낸다.
      if (event.eventVersion() != 1
          || !paymentId.equals(event.aggregateId())
          || !paymentId.toString().equals(record.key())) {
        throw new IllegalArgumentException();
      }
      return event;
    } catch (IOException | RuntimeException exception) {
      // Jackson 예외의 원문 입력을 프레임워크 로그로 전달하지 않는다.
      throw new IllegalArgumentException("Invalid settlement reply record");
    }
  }

  private static UUID paymentId(Object payload) {
    return switch (payload) {
      case RefundReservationResultPayload reply -> reply.paymentId();
      case RefundLedgerReversedPayload reply -> reply.paymentId();
      case RefundReservationReleasedPayload reply -> reply.paymentId();
      default -> throw new IllegalArgumentException();
    };
  }

  /** 응답 종류별 해석기와 형식을 따로 검사할 UUID·nullable UUID·시각 필드. */
  private record Reply(
      ObjectReader reader, List<String> uuids, List<String> nullableUuids, List<String> times) {}
}
