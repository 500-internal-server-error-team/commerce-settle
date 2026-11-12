package com.commercesettle.payment.infrastructure.persistence.outbox;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationResultPayload;
import com.commercesettle.payment.application.event.PaymentConfirmedPayload;
import com.commercesettle.payment.application.event.PaymentDeclinedPayload;
import com.commercesettle.payment.application.event.PaymentNoticeRequestedPayload;
import com.commercesettle.payment.application.event.RefundAcceptedPayload;
import com.commercesettle.payment.application.event.RefundCompletedPayload;
import com.commercesettle.payment.application.event.RefundEventPayload;
import com.commercesettle.payment.application.event.RefundFailedPayload;
import com.commercesettle.payment.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.payment.application.event.RefundReleaseRequestedPayload;
import com.commercesettle.payment.application.event.RefundReservationRequestedPayload;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.infrastructure.messaging.OutboxAppended;
import com.commercesettle.payment.infrastructure.persistence.TransactionLimits;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcPaymentEventOutbox implements PaymentEventOutbox {
  /** 발행하는 환불 이벤트 종류와 본문 타입. */
  private static final Map<String, Class<? extends RefundEventPayload>> REFUND_EVENTS =
      Map.of(
          "RefundAccepted", RefundAcceptedPayload.class,
          "RefundReservationRequested", RefundReservationRequestedPayload.class,
          "RefundFinancialCompleted", RefundFinancialCompletedPayload.class,
          "RefundReleaseRequested", RefundReleaseRequestedPayload.class,
          "RefundCompleted", RefundCompletedPayload.class,
          "RefundFailed", RefundFailedPayload.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private AsyncObservation observations = AsyncObservation.NOOP;
  private final ApplicationEventPublisher events;

  public JdbcPaymentEventOutbox(
      NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.events = events;
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendNoticeRequested(EventEnvelope<PaymentNoticeRequestedPayload> event) {
    if (!"PaymentNoticeRequested".equals(event.eventType())) {
      throw new IllegalArgumentException("Invalid payment notice event");
    }
    return append(event, event.payload().paymentId());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentAccepted(EventEnvelope<PaymentAcceptedPayload> event) {
    // aggregateVersion은 결제 버전이므로 payload의 주문 버전과 비교하지 않는다.
    if (!"PaymentAccepted".equals(event.eventType())) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    return append(event, event.payload().paymentId());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendCancellationResult(EventEnvelope<PaymentCancellationResultPayload> event) {
    if (!"PaymentCancelled".equals(event.eventType())
        && !"PaymentCancellationRejected".equals(event.eventType())) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    return append(event, event.payload().paymentId());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentConfirmed(EventEnvelope<PaymentConfirmedPayload> event) {
    if (!"PaymentConfirmed".equals(event.eventType())) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    return append(event, event.payload().paymentId());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentDeclined(EventEnvelope<PaymentDeclinedPayload> event) {
    if (!"PaymentDeclined".equals(event.eventType())) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    return append(event, event.payload().paymentId());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendRefundEvent(EventEnvelope<? extends RefundEventPayload> event) {
    Class<?> payload = REFUND_EVENTS.get(event.eventType());
    if (payload == null || !payload.isInstance(event.payload())) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    return append(event, event.payload().paymentId());
  }

  private UUID append(EventEnvelope<?> event, UUID paymentId) {
    if (event.eventVersion() != 1 || !event.aggregateId().equals(paymentId)) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    byte[] body;
    try {
      body = mapper.writeValueAsBytes(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot serialize payment outbox event");
    }
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    var params =
        new MapSqlParameterSource("id", event.eventId())
            .addValue("aggregate", event.aggregateId())
            .addValue("version", event.aggregateVersion())
            .addValue("occurred", event.occurredAt().toString())
            .addValue("body", body)
            .addValue("json", new String(body, StandardCharsets.UTF_8))
            .addValue("traceParent", observations.capture())
            .addValue(
                "orderId",
                event.payload() instanceof PaymentConfirmedPayload confirmed
                    ? confirmed.orderId()
                    : null)
            .addValue(
                "cycleId",
                event.payload() instanceof PaymentConfirmedPayload confirmed
                        && confirmed.paymentCycleId() != null
                    ? confirmed.paymentCycleId().toString()
                    : null)
            .addValue("type", event.eventType())
            .addValue("key", paymentId.toString());
    // 집계별 append를 커밋까지 직렬화하여 늦은 낮은 버전의 삽입을 막는다.
    lockAggregate(event.aggregateId(), params);
    // 기존 ID 확인·최신 버전 확인·삽입을 한 문장에서 같은 스냅샷으로 처리한다. existing은 삽입 전 행이다.
    var result =
        jdbc.queryForObject(
            """
            WITH latest AS (
                SELECT coalesce(max(aggregate_version), -1) AS version FROM payment.outbox
                WHERE aggregate_id = :aggregate),
            existing AS (SELECT raw_body FROM payment.outbox WHERE id = :id),
            inserted AS (
                INSERT INTO payment.outbox (id,event_type,event_version,aggregate_id,
                    aggregate_version,topic,occurred_at,message_key,payload,raw_body,trace_parent,
                    order_accepted_at)
                SELECT :id,:type,1,:aggregate,:version,'commerce-settle.payment.v1',
                    CAST(:occurred AS timestamptz),:key,CAST(:json AS jsonb),:body,:traceParent,
                    (SELECT i.order_accepted_at FROM payment.inbox i
                     WHERE :type='PaymentConfirmed' AND i.event_type='OrderPaymentRequested'
                       AND i.aggregate_id=CAST(:orderId AS uuid)
                       AND i.status IN ('WAITING','APPLIED')
                       AND i.payload->'payload'->>'paymentCycleId'=:cycleId
                     ORDER BY i.created_at,i.id LIMIT 1)
                FROM latest
                WHERE NOT EXISTS (SELECT 1 FROM existing) AND :version >= latest.version
                ON CONFLICT (id) DO NOTHING
                RETURNING id)
            SELECT (SELECT count(*) FROM inserted) AS inserted,
                (SELECT version FROM latest) AS latest,
                (SELECT raw_body FROM existing) AS existing
            """,
            params,
            (rs, row) ->
                new AppendResult(
                    rs.getLong("inserted") == 1, rs.getLong("latest"), rs.getBytes("existing")));
    if (result.inserted()) {
      events.publishEvent(new OutboxAppended());
    } else if (result.existing() != null) {
      requireSameBody(result.existing(), body);
    } else if (event.aggregateVersion() < result.latest()) {
      throw new IllegalArgumentException("Payment outbox version moved backwards");
    } else {
      // 같은 ID를 다른 집계 잠금 아래에서 먼저 넣은 경우다.
      byte[] original =
          jdbc.queryForObject(
              "SELECT raw_body FROM payment.outbox WHERE id = :id",
              Map.of("id", event.eventId()),
              byte[].class);
      requireSameBody(original, body);
    }
    return event.eventId();
  }

  /** 같은 물리 트랜잭션에서 이미 잡은 집계 잠금은 다시 요청하지 않는다. 잠금은 트랜잭션 종료까지 유지된다. */
  private void lockAggregate(UUID aggregateId, MapSqlParameterSource params) {
    Set<UUID> locked = lockedAggregates();
    if (locked != null && locked.contains(aggregateId)) {
      return;
    }
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:aggregate AS text), 0))", params);
    if (locked != null) {
      locked.add(aggregateId);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<UUID> lockedAggregates() {
    var source = jdbc.getJdbcTemplate().getDataSource();
    Object holder =
        source == null || !TransactionSynchronizationManager.isSynchronizationActive()
            ? null
            : TransactionSynchronizationManager.getResource(source);
    if (holder == null) {
      return null;
    }
    var key = new LockedAggregates(holder);
    Object bound = TransactionSynchronizationManager.getResource(key);
    if (bound != null) {
      return (Set<UUID>) bound;
    }
    Set<UUID> locked = new HashSet<>();
    TransactionSynchronizationManager.bindResource(key, locked);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
          }
        });
    return locked;
  }

  /** 커넥션 홀더의 동일성으로 물리 트랜잭션을 구분한다. */
  private record LockedAggregates(Object holder) {}

  private record AppendResult(boolean inserted, long latest, byte[] existing) {}

  private static void requireSameBody(byte[] original, byte[] body) {
    if (!Arrays.equals(original, body)) {
      throw new IllegalArgumentException("Outbox event ID has a different body");
    }
  }
}
