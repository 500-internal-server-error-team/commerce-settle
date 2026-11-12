package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 작업 임대는 업무 상태 전이가 아니라 실행권 선점이라 조건부 SQL로 처리하고, 만료는 DB 시각으로 판정한다. */
@Repository
class JpaPaymentJobRepository implements PaymentJobRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataPaymentJobRepository repository;
  private final NamedParameterJdbcTemplate jdbc;
  private AsyncObservation observations = AsyncObservation.NOOP;

  JpaPaymentJobRepository(
      SpringDataPaymentJobRepository repository, NamedParameterJdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  @Override
  public PaymentJob save(PaymentJob job) {
    if (job.getVersion() == null) job.captureTraceParent(observations.capture());
    return repository.save(job);
  }

  @Override
  public Optional<PaymentJob> findByOperationAndBusinessKey(
      PaymentJobOperation operation, UUID businessKey) {
    return repository.findByOperationAndBusinessKey(operation, businessKey);
  }

  @Override
  @Transactional
  public Optional<LeasedJob> leaseNext(
      PaymentJobOperation operation, String owner, Duration leaseDuration) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    // ix_payment_jobs_lease를 next_run_at 순서로 읽다가 첫 후보에서 멈춘다. 임대는 next_run_at이 지난 작업에만
    // 걸리므로 RUNNING 작업도 next_run_at이 현재 이전이다. 고정 시각 조건만 인덱스 범위로 쓸 수 있다.
    return jdbc
        .query(
            """
            WITH candidate AS (
              SELECT id,created_at AS queued_created,updated_at AS queued_changed,
                  next_run_at AS queued_due,lease_until AS queued_lease,status AS queued_status
              FROM payment.payment_jobs WHERE operation = :operation
                AND status IN ('PENDING', 'RETRY', 'RUNNING') AND next_run_at <= statement_timestamp()
                AND (status <> 'RUNNING' OR lease_until < clock_timestamp())
              ORDER BY next_run_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE payment.payment_jobs j
            SET status = 'RUNNING', lease_owner = :owner, lease_token = :token,
                lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                attempts = j.attempts + 1, updated_at = clock_timestamp(), version = j.version + 1
            FROM candidate c WHERE j.id = c.id
            RETURNING j.id,j.payment_id,j.business_key,j.lease_token,j.attempts,j.trace_parent,
                c.queued_created,c.queued_changed,c.queued_status,
                CASE WHEN c.queued_status='RUNNING' THEN c.queued_lease ELSE c.queued_due END AS queued_eligible
            """,
            new MapSqlParameterSource()
                .addValue("operation", operation.name())
                .addValue("owner", owner)
                .addValue("token", UUID.randomUUID())
                .addValue("leaseSeconds", seconds(leaseDuration)),
            (rs, row) ->
                new LeasedJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    rs.getObject("business_key", UUID.class),
                    rs.getObject("lease_token", UUID.class),
                    rs.getInt("attempts"),
                    rs.getTimestamp("queued_created").toInstant(),
                    rs.getString("trace_parent"),
                    new Timing(
                        rs.getTimestamp("queued_created").toInstant(),
                        rs.getTimestamp("queued_changed").toInstant(),
                        rs.getTimestamp("queued_eligible").toInstant(),
                        rs.getString("queued_status"),
                        rs.getInt("attempts"))))
        .stream()
        .findFirst();
  }

  @Override
  public boolean holdsLease(UUID jobId, UUID leaseToken, Duration minRemaining) {
    Boolean holds =
        jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1 FROM payment.payment_jobs WHERE id = :id
              AND status = 'RUNNING' AND lease_token = :token
              AND lease_until > clock_timestamp() + make_interval(secs => :minRemaining))
            """,
            new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("token", leaseToken)
                .addValue("minRemaining", seconds(minRemaining)),
            Boolean.class);
    return Boolean.TRUE.equals(holds);
  }

  @Override
  public boolean releaseLease(
      UUID jobId, UUID leaseToken, PaymentJobStatus status, Duration runAfter, String errorCode) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    // 임대 만료는 실제 현재 시각으로 본다. now()는 트랜잭션 시작 시각이라 잠금 대기 중 만료된 임대도 통과시킨다.
    return jdbc.update(
            """
            UPDATE payment.payment_jobs
            SET status = :status,
                next_run_at = clock_timestamp() + make_interval(secs => :runAfter),
                lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                last_error_code = coalesce(:errorCode, last_error_code),
                updated_at = clock_timestamp(), version = version + 1
            WHERE id = :id AND status = 'RUNNING' AND lease_token = :token
              AND lease_until > clock_timestamp()
            """,
            new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("token", leaseToken)
                .addValue("status", status.name())
                .addValue("runAfter", seconds(runAfter))
                .addValue("errorCode", errorCode, Types.VARCHAR))
        == 1;
  }

  @Override
  public Optional<PaymentJob> findById(UUID jobId) {
    return repository.findById(jobId);
  }

  @Override
  public List<PaymentJob> findPage(int page, int size) {
    return repository.findAll(PageRequest.of(page, size, NEWEST_FIRST)).getContent();
  }

  @Override
  public long count() {
    return repository.count();
  }

  @Override
  public ReplayResult replay(UUID jobId, long expectedVersion) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    var params = new MapSqlParameterSource().addValue("id", jobId);
    var current =
        jdbc.query(
            """
            SELECT status, version,
                   (status = 'RUNNING' AND lease_until > clock_timestamp()) AS leased
            FROM payment.payment_jobs WHERE id = :id FOR UPDATE
            """,
            params,
            (rs, row) ->
                new Current(
                    PaymentJobStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    rs.getBoolean("leased")));
    if (current.isEmpty()) {
      return new ReplayResult.NotFound();
    }
    Current job = current.getFirst();
    if (job.version() != expectedVersion) {
      return new ReplayResult.VersionConflict();
    }
    if (job.status() == PaymentJobStatus.DONE || job.leased()) {
      return new ReplayResult.StateConflict(job.status());
    }
    jdbc.update(
        """
        UPDATE payment.payment_jobs
        SET status = 'PENDING', attempts = 0, next_run_at = clock_timestamp(),
            lease_owner = NULL, lease_token = NULL, lease_until = NULL,
            updated_at = clock_timestamp(), version = version + 1
        WHERE id = :id
        """,
        params);
    return new ReplayResult.Replayed(job.status());
  }

  private record Current(PaymentJobStatus status, long version, boolean leased) {}

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
