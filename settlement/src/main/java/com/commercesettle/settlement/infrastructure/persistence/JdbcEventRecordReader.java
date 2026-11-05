package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.port.EventRecordReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcEventRecordReader implements EventRecordReader {
  private static final List<String> REFERENCES =
      List.of("orderId", "paymentId", "refundId", "requestId", "payoutId", "batchId");
  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper mapper;

  public JdbcEventRecordReader(NamedParameterJdbcOperations jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public List<Entry> find(List<UUID> ids) {
    return jdbc.query(
        """
        SELECT * FROM (
          SELECT 'OUTBOX' AS kind, id, id AS event_id, event_type, aggregate_id, status, topic,
            NULL::varchar AS consumer, published_partition AS partition, published_offset AS event_offset,
            attempts, NULL::varchar AS last_error_code, NULL::uuid AS result_ref, created_at, updated_at, payload
          FROM settlement.outbox
          UNION ALL
          SELECT 'INBOX', id, event_id, event_type, aggregate_id, status, source_topic,
            consumer, source_partition, source_offset, attempts, last_error_code, result_ref, created_at, updated_at, payload
          FROM settlement.inbox
        ) records
        WHERE event_id IN (:ids) OR aggregate_id IN (:ids) OR result_ref IN (:ids)
          OR EXISTS (SELECT 1 FROM jsonb_each_text(records.payload->'payload') refs
            WHERE refs.key IN (:keys) AND lower(refs.value) IN (:textIds))
        ORDER BY created_at DESC, event_id DESC, kind, id DESC LIMIT 51
        """,
        Map.of(
            "ids", ids, "textIds", ids.stream().map(UUID::toString).toList(), "keys", REFERENCES),
        this::entry);
  }

  private Entry entry(ResultSet rs, int row) throws SQLException {
    var refs = new LinkedHashSet<UUID>();
    UUID aggregate = rs.getObject("aggregate_id", UUID.class);
    UUID result = rs.getObject("result_ref", UUID.class);
    refs.add(aggregate);
    if (result != null) refs.add(result);
    try {
      var payload = mapper.readTree(rs.getString("payload")).path("payload");
      for (String key : REFERENCES) {
        String value = payload.path(key).asText("");
        try {
          UUID id = UUID.fromString(value);
          if (id.toString().equalsIgnoreCase(value)) refs.add(id);
        } catch (IllegalArgumentException ignored) {
          // 참조 형식이 아닌 추가 필드 때문에 원래 수신 기록의 조회를 실패시키지 않는다.
        }
      }
    } catch (java.io.IOException e) {
      throw new SQLException("Stored event envelope cannot be read", e);
    }
    return new Entry(
        rs.getString("kind"),
        rs.getObject("id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        aggregate,
        rs.getString("status"),
        rs.getString("topic"),
        rs.getString("consumer"),
        rs.getObject("partition", Integer.class),
        rs.getObject("event_offset", Long.class),
        rs.getInt("attempts"),
        rs.getString("last_error_code"),
        result,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        List.copyOf(refs));
  }
}
