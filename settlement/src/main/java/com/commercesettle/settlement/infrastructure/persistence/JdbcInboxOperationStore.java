package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.exception.OperationRejectedException;
import com.commercesettle.settlement.application.exception.OperationRejectedException.Reason;
import com.commercesettle.settlement.application.port.InboxOperationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcInboxOperationStore implements InboxOperationStore {
  private static final String FILTER =
      """
      consumer=:consumer AND status='REVIEW_REQUIRED'
      AND (:reason IS NULL OR coalesce(last_error_code,'UNRECORDED')=:reason)
      AND (:type IS NULL OR event_type=:type) AND created_at<=:before
      AND (CAST(:inboxId AS uuid) IS NULL OR id=:inboxId)
      """;
  private static final String RUN_SELECT =
      """
      SELECT r.*, (SELECT count(*) FROM settlement.inbox_rejudgement_targets t
        WHERE t.run_id=r.id AND t.status IN ('DONE','CANCELLED')) AS completed_count
      FROM settlement.inbox_rejudgement_runs r
      """;
  private final NamedParameterJdbcOperations jdbc;
  private final String consumer;

  JdbcInboxOperationStore(
      NamedParameterJdbcOperations jdbc,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer) {
    this.jdbc = jdbc;
    this.consumer = consumer;
  }

  private void limits() {
    jdbc.getJdbcOperations()
        .queryForList(
            """
            SELECT set_config(budget.name,
              least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),budget.timeout_ms)::text,true)
            FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000)) AS budget(name,timeout_ms)
            JOIN pg_settings settings ON settings.name=budget.name
            """);
  }

  private MapSqlParameterSource params() {
    return new MapSqlParameterSource("consumer", consumer);
  }

  private MapSqlParameterSource filter(Filter f) {
    return params()
        .addValue("reason", f.reasonCode(), Types.VARCHAR)
        .addValue("type", f.eventType(), Types.VARCHAR)
        .addValue("before", f.receivedBefore().atOffset(ZoneOffset.UTC))
        .addValue("inboxId", f.inboxId(), Types.OTHER);
  }

  @Override
  public List<Summary> summary() {
    limits();
    return jdbc.query(
        """
        SELECT coalesce(last_error_code,'UNRECORDED') AS reason,event_type,count(*) AS records,
        greatest(0,extract(epoch FROM(clock_timestamp()-min(updated_at))))::double precision AS age
        FROM settlement.inbox WHERE consumer=:consumer AND status='REVIEW_REQUIRED'
        GROUP BY last_error_code,event_type ORDER BY reason,event_type
        """,
        params(),
        (rs, n) ->
            new Summary(
                rs.getString("reason"),
                rs.getString("event_type"),
                rs.getLong("records"),
                rs.getDouble("age")));
  }

  @Override
  public PageView<InboxView> list(Filter f, int page, int size) {
    limits();
    var p = filter(f).addValue("limit", size).addValue("offset", (long) page * size);
    var rows =
        jdbc.query(
            "SELECT id,event_id,event_type,last_error_code,status,version,body_hash,created_at,updated_at,octet_length(raw_body) AS body_size FROM settlement.inbox WHERE "
                + FILTER
                + " ORDER BY created_at,id LIMIT :limit OFFSET :offset",
            p,
            (rs, n) -> inbox(rs));
    long count =
        jdbc.queryForObject("SELECT count(*) FROM settlement.inbox WHERE " + FILTER, p, Long.class);
    return new PageView<>(rows, page, size, count);
  }

  @Override
  public Optional<InboxView> find(UUID id) {
    limits();
    return jdbc
        .query(
            "SELECT id,event_id,event_type,last_error_code,status,version,body_hash,created_at,updated_at,octet_length(raw_body) AS body_size FROM settlement.inbox WHERE consumer=:consumer AND id=:id",
            params().addValue("id", id),
            (rs, n) -> inbox(rs))
        .stream()
        .findFirst();
  }

  private static InboxView inbox(ResultSet r) throws SQLException {
    return new InboxView(
        r.getObject("id", UUID.class),
        r.getObject("event_id", UUID.class),
        r.getString("event_type"),
        r.getString("last_error_code"),
        r.getString("status"),
        r.getLong("version"),
        r.getLong("body_size"),
        r.getString("body_hash"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant());
  }

  @Override
  public RunView create(
      Filter f, List<ExpectedTarget> expected, int count, String operator, String reason) {
    limits();
    var selected =
        jdbc.query(
            "SELECT id,version FROM settlement.inbox WHERE "
                + FILTER
                + " ORDER BY created_at,id LIMIT 101 FOR UPDATE",
            filter(f),
            (rs, n) -> new ExpectedTarget(rs.getObject("id", UUID.class), rs.getLong("version")));
    if (selected.size() != count
        || selected.size() > 100
        || !new HashSet<>(selected).equals(new HashSet<>(expected)))
      throw new OperationRejectedException(Reason.INBOX_TARGETS_CHANGED);
    var ids = selected.stream().map(ExpectedTarget::inboxId).toList();
    long active =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.inbox_rejudgement_targets WHERE inbox_id IN (:ids) AND status IN ('PENDING','RUNNING')",
            new MapSqlParameterSource("ids", ids),
            Long.class);
    if (active != 0) throw new OperationRejectedException(Reason.INBOX_REJUDGEMENT_ACTIVE);
    UUID run = UUID.randomUUID();
    var p =
        filter(f)
            .addValue("id", run)
            .addValue("count", count)
            .addValue("operator", operator)
            .addValue("operatorReason", reason);
    jdbc.update(
        """
        INSERT INTO settlement.inbox_rejudgement_runs(id,consumer,status,filter_reason,filter_event_type,received_before,filter_inbox_id,expected_count,requested_by,reason_code)
        VALUES(:id,:consumer,'RUNNING',:reason,:type,:before,:inboxId,:count,:operator,:operatorReason)
        """,
        p);
    jdbc.update(
        """
        INSERT INTO settlement.inbox_rejudgement_targets(id,run_id,inbox_id,event_id,event_type,reason_code,expected_version,position,status)
        SELECT gen_random_uuid(),:run,id,event_id,event_type,last_error_code,version,
          (row_number() OVER(ORDER BY created_at,id)-1)::integer,'PENDING'
        FROM settlement.inbox WHERE id IN (:ids)
        """,
        new MapSqlParameterSource("run", run).addValue("ids", ids));
    return findRun(run).orElseThrow();
  }

  @Override
  public Optional<RunView> findRun(UUID id) {
    limits();
    return jdbc
        .query(
            RUN_SELECT + " WHERE r.consumer=:consumer AND r.id=:id",
            params().addValue("id", id),
            (rs, n) -> run(rs))
        .stream()
        .findFirst();
  }

  @Override
  public PageView<RunView> runs(int page, int size) {
    limits();
    var p = params().addValue("limit", size).addValue("offset", (long) page * size);
    var rows =
        jdbc.query(
            RUN_SELECT
                + " WHERE r.consumer=:consumer ORDER BY r.created_at DESC,r.id DESC LIMIT :limit OFFSET :offset",
            p,
            (rs, n) -> run(rs));
    long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.inbox_rejudgement_runs WHERE consumer=:consumer",
            p,
            Long.class);
    return new PageView<>(rows, page, size, count);
  }

  private static RunView run(ResultSet r) throws SQLException {
    return new RunView(
        r.getObject("id", UUID.class),
        r.getString("status"),
        r.getLong("version"),
        new Filter(
            r.getString("filter_reason"),
            r.getString("filter_event_type"),
            r.getTimestamp("received_before").toInstant(),
            r.getObject("filter_inbox_id", UUID.class)),
        r.getInt("expected_count"),
        r.getString("requested_by"),
        r.getString("reason_code"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant(),
        r.getLong("completed_count"));
  }

  @Override
  public PageView<TargetView> targets(UUID runId, int page, int size) {
    limits();
    var p =
        params()
            .addValue("id", runId)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
    var rows =
        jdbc.query(
            """
        SELECT t.* FROM settlement.inbox_rejudgement_targets t JOIN settlement.inbox_rejudgement_runs r ON r.id=t.run_id
        WHERE r.id=:id AND r.consumer=:consumer ORDER BY t.position LIMIT :limit OFFSET :offset
        """,
            p,
            (r, n) ->
                new TargetView(
                    r.getObject("id", UUID.class),
                    r.getObject("inbox_id", UUID.class),
                    r.getObject("event_id", UUID.class),
                    r.getString("event_type"),
                    r.getString("reason_code"),
                    r.getLong("expected_version"),
                    r.getString("status"),
                    r.getString("outcome"),
                    r.getInt("attempts"),
                    r.getTimestamp("lease_until") == null
                        ? null
                        : r.getTimestamp("lease_until").toInstant(),
                    r.getObject("result_version", Long.class),
                    r.getString("result_reason_code")));
    long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.inbox_rejudgement_targets t JOIN settlement.inbox_rejudgement_runs r ON r.id=t.run_id WHERE r.id=:id AND r.consumer=:consumer",
            p,
            Long.class);
    return new PageView<>(rows, page, size, count);
  }

  @Override
  public PageView<AttemptView> attempts(UUID runId, int page, int size) {
    limits();
    var p =
        params()
            .addValue("id", runId)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
    String from =
        " FROM settlement.inbox_rejudgement_attempts a JOIN settlement.inbox_rejudgement_runs r ON r.id=a.run_id WHERE r.id=:id AND r.consumer=:consumer";
    var rows =
        jdbc.query(
            "SELECT a.*" + from + " ORDER BY a.started_at,a.id LIMIT :limit OFFSET :offset",
            p,
            (r, n) ->
                new AttemptView(
                    r.getObject("id", UUID.class),
                    r.getObject("target_id", UUID.class),
                    r.getInt("attempt"),
                    r.getString("outcome"),
                    r.getTimestamp("started_at").toInstant(),
                    r.getTimestamp("finished_at") == null
                        ? null
                        : r.getTimestamp("finished_at").toInstant()));
    return new PageView<>(
        rows, page, size, jdbc.queryForObject("SELECT count(*)" + from, p, Long.class));
  }

  @Override
  public PageView<AuditView> audits(UUID runId, int page, int size) {
    limits();
    var p =
        params()
            .addValue("id", runId)
            .addValue("correlation", runId.toString())
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
    String from =
        " FROM settlement.audit_logs a JOIN settlement.inbox_rejudgement_runs r ON r.id=:id AND r.consumer=:consumer WHERE a.correlation_id=:correlation AND a.aggregate_type IN ('INBOX_REJUDGEMENT','INBOX_REJUDGEMENT_TARGET')";
    var rows =
        jdbc.query(
            "SELECT a.*" + from + " ORDER BY a.occurred_at,a.id LIMIT :limit OFFSET :offset",
            p,
            (rs, n) ->
                new AuditView(
                    rs.getObject("id", UUID.class),
                    rs.getString("actor_kind"),
                    rs.getString("actor_ref"),
                    rs.getString("action"),
                    rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getString("before_status"),
                    rs.getString("after_status"),
                    rs.getString("reason_code"),
                    rs.getTimestamp("occurred_at").toInstant()));
    return new PageView<>(
        rows, page, size, jdbc.queryForObject("SELECT count(*)" + from, p, Long.class));
  }

  @Override
  public void transition(UUID id, long version, String action) {
    limits();
    var p = params().addValue("id", id).addValue("version", version);
    var states =
        jdbc.query(
            "SELECT status,version FROM settlement.inbox_rejudgement_runs WHERE consumer=:consumer AND id=:id FOR UPDATE",
            p,
            (r, n) -> new Object[] {r.getString(1), r.getLong(2)});
    if (states.isEmpty()) throw new IllegalArgumentException("Run not found");
    if ((long) states.getFirst()[1] != version)
      throw new OperationRejectedException(Reason.VERSION_CONFLICT);
    String state = (String) states.getFirst()[0];
    String next =
        switch (action) {
          case "PAUSE" -> {
            if (!state.equals("RUNNING"))
              throw new OperationRejectedException(Reason.INBOX_RUN_STATE_CONFLICT);
            yield "PAUSED";
          }
          case "RESUME" -> {
            if (!state.equals("PAUSED"))
              throw new OperationRejectedException(Reason.INBOX_RUN_STATE_CONFLICT);
            yield "RUNNING";
          }
          case "CANCEL" -> {
            if (!List.of("RUNNING", "PAUSED").contains(state))
              throw new OperationRejectedException(Reason.INBOX_RUN_STATE_CONFLICT);
            yield "CANCELLED";
          }
          default -> throw new IllegalArgumentException("Invalid action");
        };
    p.addValue("next", next).addValue("outcome", action.equals("CANCEL") ? "CANCELLED" : "PAUSED");
    if (!action.equals("RESUME")) {
      jdbc.update(
          "UPDATE settlement.inbox_rejudgement_attempts SET outcome=:outcome,finished_at=clock_timestamp() WHERE run_id=:id AND outcome='RUNNING'",
          p);
      jdbc.update(
          "UPDATE settlement.inbox_rejudgement_targets SET status=:state,lease_token=NULL,lease_until=NULL,outcome=:outcome WHERE run_id=:id AND status IN ('PENDING','RUNNING')",
          p.addValue("state", action.equals("CANCEL") ? "CANCELLED" : "PENDING"));
    }
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_runs SET status=:next,version=version+1,updated_at=clock_timestamp() WHERE id=:id",
        p);
  }

  @Override
  public Optional<Claim> claim(Duration lease) {
    limits();
    var runs =
        jdbc.query(
            """
        SELECT r.id FROM settlement.inbox_rejudgement_runs r WHERE r.consumer=:consumer AND r.status='RUNNING'
        AND EXISTS (SELECT 1 FROM settlement.inbox_rejudgement_targets t WHERE t.run_id=r.id AND t.status IN ('PENDING','RUNNING'))
        AND NOT EXISTS (SELECT 1 FROM settlement.inbox_rejudgement_targets t WHERE t.run_id=r.id AND t.status='RUNNING' AND t.lease_until>clock_timestamp())
        ORDER BY r.created_at,r.id LIMIT 1 FOR UPDATE SKIP LOCKED
        """,
            params(),
            (rs, n) -> rs.getObject(1, UUID.class));
    if (runs.isEmpty()) return Optional.empty();
    UUID runId = runs.getFirst();
    var p = new MapSqlParameterSource("run", runId);
    var rows =
        jdbc.query(
            "SELECT * FROM settlement.inbox_rejudgement_targets WHERE run_id=:run AND status IN ('PENDING','RUNNING') ORDER BY position LIMIT 1 FOR UPDATE",
            p,
            (rs, n) ->
                new Claim(
                    runId,
                    rs.getObject("id", UUID.class),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    rs.getObject("inbox_id", UUID.class),
                    rs.getLong("expected_version"),
                    rs.getString("reason_code"),
                    rs.getInt("attempts"),
                    rs.getString("status")));
    Claim claim = rows.getFirst();
    p.addValue("id", claim.targetId())
        .addValue("token", claim.token())
        .addValue("attemptId", claim.attemptId())
        .addValue("lease", lease.toMillis() / 1000.0);
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_attempts SET outcome='LEASE_EXPIRED',finished_at=clock_timestamp() WHERE target_id=:id AND outcome='RUNNING'",
        p);
    if (claim.exhausted()) {
      jdbc.update(
          "UPDATE settlement.inbox_rejudgement_targets SET status='DONE',outcome='ATTEMPTS_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE id=:id",
          p);
      completeRun(runId);
      return Optional.of(claim);
    }
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_targets SET status='RUNNING',attempts=attempts+1,outcome=NULL,lease_token=:token,lease_until=clock_timestamp()+make_interval(secs=>:lease) WHERE id=:id",
        p);
    jdbc.update(
        "INSERT INTO settlement.inbox_rejudgement_attempts(id,run_id,target_id,attempt,outcome) SELECT :attemptId,run_id,id,attempts,'RUNNING' FROM settlement.inbox_rejudgement_targets WHERE id=:id",
        p);
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_runs SET updated_at=clock_timestamp() WHERE id=:run",
        p);
    return Optional.of(claim);
  }

  @Override
  public boolean lockClaim(Claim c) {
    limits();
    var p =
        params()
            .addValue("run", c.runId())
            .addValue("id", c.targetId())
            .addValue("token", c.token());
    var run =
        jdbc.query(
            "SELECT id FROM settlement.inbox_rejudgement_runs WHERE consumer=:consumer AND id=:run AND status='RUNNING' FOR UPDATE",
            p,
            (rs, n) -> rs.getObject(1, UUID.class));
    if (run.isEmpty()) return false;
    return !jdbc.query(
            "SELECT id FROM settlement.inbox_rejudgement_targets WHERE run_id=:run AND id=:id AND status='RUNNING' AND lease_token=:token AND lease_until>clock_timestamp() FOR UPDATE",
            p,
            (rs, n) -> rs.getObject(1, UUID.class))
        .isEmpty();
  }

  @Override
  public void finish(Claim c, String outcome) {
    var p =
        new MapSqlParameterSource("id", c.targetId())
            .addValue("run", c.runId())
            .addValue("token", c.token())
            .addValue("attemptId", c.attemptId())
            .addValue("outcome", outcome);
    // lockClaim fenced the claim in this transaction. A lease expiring during business processing
    // cannot be stolen while the run lock is held.
    int changed =
        jdbc.update(
            """
        UPDATE settlement.inbox_rejudgement_targets t SET status='DONE',outcome=:outcome,lease_token=NULL,lease_until=NULL,
          result_version=i.version,result_reason_code=i.last_error_code
        FROM settlement.inbox i WHERE t.id=:id AND t.inbox_id=i.id AND t.status='RUNNING' AND t.lease_token=:token
        """,
            p);
    if (changed != 1) throw new IllegalStateException("Rejudgement claim changed");
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_attempts SET outcome=:outcome,finished_at=clock_timestamp() WHERE id=:attemptId AND outcome='RUNNING'",
        p);
    completeRun(c.runId());
  }

  private void completeRun(UUID runId) {
    jdbc.update(
        """
        UPDATE settlement.inbox_rejudgement_runs r SET updated_at=clock_timestamp(),
          status=CASE WHEN EXISTS(SELECT 1 FROM settlement.inbox_rejudgement_targets t WHERE t.run_id=r.id AND t.status IN ('PENDING','RUNNING')) THEN status ELSE 'COMPLETED' END,
          version=version+CASE WHEN EXISTS(SELECT 1 FROM settlement.inbox_rejudgement_targets t WHERE t.run_id=r.id AND t.status IN ('PENDING','RUNNING')) THEN 0 ELSE 1 END
        WHERE id=:id
        """,
        new MapSqlParameterSource("id", runId));
  }
}
