package com.commercesettle.commerce.infrastructure.persistence.notification;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.application.service.NotificationTemplates;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.model.Notification.Kind;
import com.commercesettle.commerce.domain.model.Notification.Status;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNotificationStore implements NotificationStore {
  private static final String CONSUMER = "commerce-notifications.v1";
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper mapper;

  public JdbcNotificationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.mapper = mapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(Draft draft) {
    TransactionLimits.apply(jdbc);
    validate(draft);
    UUID candidate = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    Instant received = databaseNow();
    var args =
        new MapSqlParameterSource()
            .addValue("id", item)
            .addValue("delivery", candidate)
            .addValue("source", draft.source())
            .addValue("event", draft.eventId())
            .addValue("kind", draft.kind().name())
            .addValue("business", draft.businessRef())
            .addValue("recipientKind", draft.recipientKind().name())
            .addValue("recipient", draft.recipientId())
            .addValue("occurred", time(draft.occurredAt()))
            .addValue("payload", draft.payload().toString())
            .addValue("received", time(received));
    int inserted =
        named.update(
            """
        INSERT INTO commerce.notification_items(id,delivery_id,source_service,source_event_id,kind,business_ref,recipient_kind,recipient_id,occurred_at,payload,created_at)
        VALUES (:id,:delivery,:source,:event,:kind,:business,:recipientKind,:recipient,:occurred,CAST(:payload AS jsonb),:received)
        ON CONFLICT(source_service,kind,business_ref,recipient_kind,recipient_id) DO NOTHING
        """,
            args);
    if (inserted == 0) {
      boolean same =
          named.queryForObject(
              """
          SELECT payload=CAST(:payload AS jsonb) FROM commerce.notification_items WHERE source_service=:source AND kind=:kind
          AND business_ref=:business AND recipient_kind=:recipientKind AND recipient_id=:recipient
          """,
              args,
              Boolean.class);
      if (!same)
        throw new Notification.ContentConflictException("Notification business payload conflict");
      return;
    }
    boolean sale = draft.kind() == Kind.SALE_COMPLETED;
    long total = sale ? draft.payload().required("totalAmount").longValue() : 0;
    var rendered = NotificationTemplates.render(draft, 1, total);
    UUID delivery = null;
    for (int tries = 0; tries < 2 && delivery == null; tries++) {
      Instant bucket = sale ? received.truncatedTo(ChronoUnit.MINUTES) : null;
      String key =
          sale
              ? bucket.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString()
              : draft.businessRef().toString();
      args.addValue("dedup", key)
          .addValue("total", total)
          .addValue("template", sale ? "" : NotificationTemplates.VERSION)
          .addValue("title", rendered.title())
          .addValue("body", sale ? "" : rendered.body())
          .addValue("status", sale ? "COLLECTING" : "PENDING")
          .addValue("bucket", time(bucket))
          .addValue("end", time(sale ? bucket.plusSeconds(60) : null))
          .addValue("next", time(sale ? bucket.plusSeconds(60) : received))
          .addValue("received", time(received));
      String conflict =
          sale
              ? """
          DO UPDATE SET item_count=notification_deliveries.item_count+1,
          total_amount=notification_deliveries.total_amount+EXCLUDED.total_amount,
          first_occurred_at=least(notification_deliveries.first_occurred_at,EXCLUDED.first_occurred_at),
          updated_at=EXCLUDED.updated_at,version=notification_deliveries.version+1
          WHERE notification_deliveries.status='COLLECTING'
          """
              : "DO NOTHING";
      delivery =
          named
              .query(
                  """
          INSERT INTO commerce.notification_deliveries(id,kind,recipient_kind,recipient_id,channel,dedup_key,item_count,first_occurred_at,total_amount,template_version,title,body,status,bucket_start,bucket_end,next_run_at,created_at,updated_at)
          VALUES (:delivery,:kind,:recipientKind,:recipient,'MOCK_KAKAO',:dedup,1,:occurred,:total,:template,:title,:body,:status,:bucket,:end,:next,:received,:received)
          ON CONFLICT(channel,kind,recipient_kind,recipient_id,dedup_key)
          """
                      + conflict
                      + " RETURNING id",
                  args,
                  (row, index) -> row.getObject("id", UUID.class))
              .stream()
              .findFirst()
              .orElse(null);
      if (delivery == null && sale) received = databaseNow();
    }
    if (delivery == null)
      throw new Notification.ContentConflictException("Notification bucket already closed");
    jdbc.update(
        "UPDATE commerce.notification_items SET delivery_id=?,created_at=? WHERE id=?",
        delivery,
        time(received),
        item);
  }

  private void validate(Draft draft) {
    if (!draft.payload().isObject())
      throw new Notification.ContentConflictException("Invalid notification payload");
    boolean allowed =
        switch (draft.kind()) {
          case PAYMENT_COMPLETED, SHIPMENT_STARTED, PURCHASE_CONFIRMED, RETURN_DECIDED ->
              draft.recipientKind() == ActorKind.BUYER;
          case SALE_COMPLETED, SETTLEMENT_READY, PAYOUT_COMPLETED, PAYOUT_ACTION_REQUIRED ->
              draft.recipientKind() == ActorKind.SELLER;
          default -> true;
        };
    boolean source =
        switch (draft.source()) {
          case "payment" ->
              draft.kind() == Kind.PAYMENT_COMPLETED || draft.kind() == Kind.REFUND_COMPLETED;
          case "settlement" ->
              List.of(Kind.SETTLEMENT_READY, Kind.PAYOUT_COMPLETED, Kind.PAYOUT_ACTION_REQUIRED)
                  .contains(draft.kind());
          case "commerce" ->
              !List.of(
                      Kind.PAYMENT_COMPLETED,
                      Kind.REFUND_COMPLETED,
                      Kind.SETTLEMENT_READY,
                      Kind.PAYOUT_COMPLETED,
                      Kind.PAYOUT_ACTION_REQUIRED)
                  .contains(draft.kind());
          default -> false;
        };
    if (!allowed
        || !source
        || (draft.recipientKind() == ActorKind.SELLER
            && (draft.payload().has("mileageBalanceAfter")
                || draft.payload().has("walletVersion"))))
      throw new Notification.ContentConflictException("Invalid notification recipient");
    String table = draft.recipientKind() == ActorKind.BUYER ? "buyer_accounts" : "seller_accounts";
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM commerce." + table + " WHERE id=?)",
            Boolean.class,
            draft.recipientId())))
      throw new Notification.ContentConflictException("Notification account missing");
    if (draft.kind() == Kind.SALE_COMPLETED
        && (!draft.payload().path("totalAmount").isIntegralNumber()
            || !draft.payload().path("totalAmount").canConvertToLong()
            || draft.payload().path("totalAmount").longValue() < 1))
      throw new Notification.ContentConflictException("Invalid sale amount");
  }

  @Override
  public Optional<Notification> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM commerce.notification_deliveries WHERE id=?",
            (row, index) -> notification(row),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public PageResult<Notification> page(
      ActorKind kind, UUID recipient, Status status, int page, int size) {
    checkPage(page, size);
    String where = " WHERE true";
    var args = new MapSqlParameterSource();
    if (kind != null) {
      where += " AND recipient_kind=:kind AND recipient_id=:recipient";
      args.addValue("kind", kind.name()).addValue("recipient", recipient);
    }
    if (status != null) {
      where += " AND status=:status";
      args.addValue("status", status.name());
    }
    long total =
        named.queryForObject(
            "SELECT count(*) FROM commerce.notification_deliveries" + where, args, Long.class);
    long offset = (long) page * size;
    var rows =
        offset >= total
            ? List.<Notification>of()
            : named.query(
                "SELECT * FROM commerce.notification_deliveries"
                    + where
                    + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset",
                args.addValue("limit", size).addValue("offset", offset),
                (row, index) -> notification(row));
    return new PageResult<>(rows, page, size, total);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Claim claim(Instant now) {
    TransactionLimits.apply(jdbc);
    var selected =
        jdbc.query(
            """
        SELECT * FROM commerce.notification_deliveries
        WHERE ((status IN ('PENDING','RETRY','UNKNOWN') AND next_run_at<=?)
          OR (status='COLLECTING' AND bucket_end<=statement_timestamp())
          OR (status='SENDING' AND lease_until<=?))
        ORDER BY next_run_at,id FOR UPDATE SKIP LOCKED LIMIT 1
        """,
            (row, index) -> notification(row),
            time(now),
            time(now));
    if (selected.isEmpty()) return new Claim(null, null, null);
    var n = selected.getFirst();
    var expired = n.status() == Status.SENDING ? n : null;
    boolean lookup = n.status() == Status.UNKNOWN || n.status() == Status.SENDING;
    if (n.status() == Status.SENDING) {
      jdbc.update(
          "UPDATE commerce.notification_attempts SET status='UNKNOWN',finished_at=?,error_code='NOTIFICATION_LEASE_EXPIRED' WHERE delivery_id=? AND status='RUNNING'",
          time(now),
          n.id());
    }
    if (n.retryCount() >= 10) {
      jdbc.update(
          "UPDATE commerce.notification_deliveries SET status='REVIEW_REQUIRED',lease_token=NULL,lease_until=NULL,last_error_code='NOTIFICATION_ATTEMPTS_EXHAUSTED',updated_at=?,version=version+1 WHERE id=?",
          time(now),
          n.id());
      return new Claim(find(n.id()).orElseThrow(), null, expired);
    }
    if (n.status() == Status.COLLECTING) {
      var draft =
          new Draft(
              "commerce",
              UUID.randomUUID(),
              Kind.SALE_COMPLETED,
              n.id(),
              ActorKind.SELLER,
              n.recipientId(),
              n.occurredAt(),
              mapper.createObjectNode());
      var rendered = NotificationTemplates.render(draft, n.itemCount(), n.totalAmount());
      jdbc.update(
          "UPDATE commerce.notification_deliveries SET status='PENDING',template_version=?,title=?,body=? WHERE id=?",
          NotificationTemplates.VERSION,
          rendered.title(),
          rendered.body(),
          n.id());
    }
    UUID token = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    String operation = lookup ? "LOOKUP" : "SEND";
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET status='SENDING',lease_token=?,lease_until=?,attempt_count=attempt_count+1,retry_count=retry_count+1,updated_at=?,version=version+1 WHERE id=?",
        token,
        time(now.plusSeconds(30)),
        time(now),
        n.id());
    var current = find(n.id()).orElseThrow();
    jdbc.update(
        "INSERT INTO commerce.notification_attempts(id,delivery_id,attempt_no,operation,status,started_at) VALUES (?,?,?,?,'RUNNING',?)",
        attempt,
        n.id(),
        current.attemptCount(),
        operation,
        time(now));
    return new Claim(current, new Lease(current, operation, attempt), expired);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Notification finish(
      Lease lease,
      Status requested,
      String error,
      Instant sentAt,
      String attemptStatus,
      Instant now) {
    TransactionLimits.apply(jdbc);
    var n = lease.notification();
    Status status = requested;
    if (n.retryCount() >= 10 && status != Status.SENT && status != Status.REVIEW_REQUIRED)
      status = Status.REVIEW_REQUIRED;
    int updated =
        jdbc.update(
            """
        UPDATE commerce.notification_deliveries SET status=?,last_error_code=?,sent_at=?,lease_token=NULL,lease_until=NULL,
        next_run_at=?,updated_at=?,version=version+1 WHERE id=? AND status='SENDING' AND lease_token=? AND version=? AND lease_until>?
        """,
            status.name(),
            error,
            time(sentAt),
            time(now.plusSeconds(Notification.backoffSeconds(n.retryCount()))),
            time(now),
            n.id(),
            n.leaseToken(),
            n.version(),
            time(now));
    if (updated == 1) {
      jdbc.update(
          "UPDATE commerce.notification_attempts SET status=?,finished_at=?,error_code=? WHERE id=? AND status='RUNNING'",
          attemptStatus,
          time(now),
          error,
          lease.attemptId());
      return find(n.id()).orElseThrow();
    }
    return null;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void replay(UUID id, long expected, String operator, String reason, Instant now) {
    TransactionLimits.apply(jdbc);
    var n =
        jdbc
            .query(
                "SELECT * FROM commerce.notification_deliveries WHERE id=? FOR UPDATE",
                (row, index) -> notification(row),
                id)
            .stream()
            .findFirst()
            .orElseThrow(this::notFound);
    if (n.version() != expected)
      throw new VersionConflictException("notification", expected, n.version());
    if (!n.replayable()) throw new Notification.StateConflictException();
    var last =
        jdbc.query(
            "SELECT status FROM commerce.notification_attempts WHERE delivery_id=? AND operation<>'REPLAY' ORDER BY attempt_no DESC LIMIT 1",
            (row, index) -> row.getString(1),
            id);
    boolean unknown =
        n.status() == Status.UNKNOWN || (!last.isEmpty() && last.getFirst().equals("UNKNOWN"));
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET status=?,retry_count=0,attempt_count=attempt_count+1,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error_code=NULL,updated_at=?,version=version+1 WHERE id=?",
        unknown ? "UNKNOWN" : "PENDING",
        time(now),
        time(now),
        id);
    jdbc.update(
        "INSERT INTO commerce.notification_attempts(id,delivery_id,attempt_no,operation,status,started_at,finished_at,operator_ref,reason) VALUES (?,?,?,'REPLAY','SUCCEEDED',?,?,?,?)",
        UUID.randomUUID(),
        id,
        n.attemptCount() + 1,
        time(now),
        time(now),
        operator,
        reason);
  }

  @Override
  public PageResult<Attempt> attempts(UUID id, int page, int size) {
    checkPage(page, size);
    long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM commerce.notification_attempts WHERE delivery_id=?",
            Long.class,
            id);
    long offset = (long) page * size;
    var rows =
        offset >= total
            ? List.<Attempt>of()
            : jdbc.query(
                "SELECT * FROM commerce.notification_attempts WHERE delivery_id=? ORDER BY attempt_no DESC LIMIT ? OFFSET ?",
                (row, index) ->
                    new Attempt(
                        row.getObject("id", UUID.class),
                        id,
                        row.getInt("attempt_no"),
                        row.getString("operation"),
                        row.getString("status"),
                        instant(row, "started_at"),
                        instant(row, "finished_at"),
                        row.getString("error_code"),
                        row.getString("operator_ref"),
                        row.getString("reason")),
                id,
                size,
                offset);
    return new PageResult<>(rows, page, size, total);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Acceptance accept(EventEnvelope<JsonNode> event, Source source, Instant now) {
    TransactionLimits.apply(jdbc);
    String hash = hash(source.body());
    int inserted =
        jdbc.update(
            """
        INSERT INTO commerce.inbox(id,consumer,event_id,event_type,event_version,occurred_at,payload,raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status,next_run_at)
        VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?,?,'WAITING',?)
        ON CONFLICT(consumer,event_id) DO NOTHING
        """,
            UUID.randomUUID(),
            CONSUMER,
            event.eventId(),
            event.eventType(),
            event.eventVersion(),
            event.occurredAt(),
            new String(source.body(), StandardCharsets.UTF_8),
            source.body(),
            hash,
            source.topic(),
            source.partition(),
            source.offset(),
            event.aggregateId(),
            event.aggregateVersion(),
            time(now));
    if (inserted == 1) return Acceptance.NEW;
    if (!hash.equals(
        jdbc.queryForObject(
            "SELECT body_hash FROM commerce.inbox WHERE consumer=? AND event_id=?",
            String.class,
            CONSUMER,
            event.eventId()))) {
      quarantine(source, "NOTIFICATION_EVENT_BODY_CONFLICT");
      return Acceptance.CONFLICT;
    }
    return Acceptance.DUPLICATE;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Notice> claimNotice(UUID eventId, Instant now) {
    TransactionLimits.apply(jdbc);
    var args = new MapSqlParameterSource("consumer", CONSUMER).addValue("now", time(now));
    String filter = eventId == null ? "" : " AND event_id=:event";
    if (eventId != null) args.addValue("event", eventId);
    return named
        .query(
            "SELECT id,payload::text,version FROM commerce.inbox WHERE consumer=:consumer AND status IN ('WAITING','RECEIVED') AND (next_run_at IS NULL OR next_run_at<=:now)"
                + filter
                + " ORDER BY next_run_at NULLS FIRST,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1",
            args,
            (row, index) ->
                new Notice(
                    row.getObject("id", UUID.class),
                    row.getLong("version"),
                    event(row.getString(2))))
        .stream()
        .findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean completeNotice(UUID id, long expectedVersion, Instant now) {
    TransactionLimits.apply(jdbc);
    return jdbc.update(
            "UPDATE commerce.inbox SET status='APPLIED',last_error_code=NULL,updated_at=?,version=version+1 WHERE id=? AND consumer=? AND version=? AND status IN ('WAITING','RECEIVED')",
            time(now),
            id,
            CONSUMER,
            expectedVersion)
        == 1;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String retryNotice(
      UUID id, long expectedVersion, String code, boolean review, Instant now) {
    TransactionLimits.apply(jdbc);
    boolean predecessor = code.equals("NOTIFICATION_ORDER_PREDECESSOR_NOT_READY");
    return jdbc
        .query(
            """
        UPDATE commerce.inbox SET status=CASE WHEN ? OR (NOT ? AND attempts>=9) THEN 'REVIEW_REQUIRED' ELSE 'WAITING' END,
        attempts=attempts+?,next_run_at=?,last_error_code=?,updated_at=?,version=version+1 WHERE id=? AND consumer=? AND version=? AND status IN ('WAITING','RECEIVED') RETURNING status
        """,
            (row, index) -> row.getString("status"),
            review,
            predecessor,
            predecessor ? 0 : 1,
            time(now.plusSeconds(1)),
            code,
            time(now),
            id,
            CONSUMER,
            expectedVersion)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void quarantine(Source source, String code) {
    TransactionLimits.apply(jdbc);
    jdbc.update(
        "INSERT INTO commerce.notification_inbox_quarantines(id,consumer,source_topic,source_partition,source_offset,message_key,raw_body,error_code) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(consumer,source_topic,source_partition,source_offset) DO NOTHING",
        UUID.randomUUID(),
        CONSUMER,
        source.topic(),
        source.partition(),
        source.offset(),
        source.key(),
        source.body(),
        code);
  }

  private Notification notification(ResultSet row) throws SQLException {
    return new Notification(
        row.getObject("id", UUID.class),
        Kind.valueOf(row.getString("kind")),
        ActorKind.valueOf(row.getString("recipient_kind")),
        row.getObject("recipient_id", UUID.class),
        row.getString("dedup_key"),
        row.getInt("item_count"),
        instant(row, "first_occurred_at"),
        row.getLong("total_amount"),
        row.getString("template_version"),
        row.getString("title"),
        row.getString("body"),
        Status.valueOf(row.getString("status")),
        row.getInt("attempt_count"),
        row.getInt("retry_count"),
        instant(row, "next_run_at"),
        row.getObject("lease_token", UUID.class),
        instant(row, "lease_until"),
        row.getString("last_error_code"),
        instant(row, "created_at"),
        instant(row, "sent_at"),
        row.getLong("version"));
  }

  private EventEnvelope<JsonNode> event(String text) {
    try {
      return mapper.readValue(text, new TypeReference<EventEnvelope<JsonNode>>() {});
    } catch (java.io.IOException e) {
      throw new Notification.ContentConflictException("Invalid notice JSON", e);
    }
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String name) throws SQLException {
    var value = row.getObject(name, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static String hash(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void checkPage(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new com.commercesettle.commerce.application.exception.OrderRequestException(
          com.commercesettle.commerce.application.exception.OrderRequestException.Kind
              .INVALID_REQUEST,
          "page");
  }

  private RuntimeException notFound() {
    return new com.commercesettle.commerce.application.exception.OrderRequestException(
        com.commercesettle.commerce.application.exception.OrderRequestException.Kind.NOT_FOUND,
        null);
  }
}
