package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 작업 임대는 업무 상태 전이가 아니라 실행권 선점이라 조건부 SQL로 처리하고, 만료는 DB 시각으로 판정한다. */
@Repository
class JpaPaymentJobRepository implements PaymentJobRepository {
  private final SpringDataPaymentJobRepository repository;
  private final NamedParameterJdbcTemplate jdbc;

  JpaPaymentJobRepository(
      SpringDataPaymentJobRepository repository, NamedParameterJdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Override
  public PaymentJob save(PaymentJob job) {
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
    limits();
    return jdbc
        .query(
            """
            UPDATE payment.payment_jobs
            SET status = 'RUNNING', lease_owner = :owner, lease_token = :token,
                lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                attempts = attempts + 1, updated_at = clock_timestamp(), version = version + 1
            WHERE id = (
              SELECT id FROM payment.payment_jobs
              WHERE operation = :operation
                AND ((status IN ('PENDING', 'RETRY') AND next_run_at <= clock_timestamp())
                  OR (status = 'RUNNING' AND lease_until < clock_timestamp()))
              ORDER BY next_run_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id, payment_id, business_key, lease_token, attempts
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
                    rs.getInt("attempts")))
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
    limits();
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

  /** 다른 트랜잭션의 작업 잠금은 1초, SQL 한 문장은 5초까지만 기다린다. 호출자의 더 짧은 상한은 유지한다. */
  private void limits() {
    jdbc.getJdbcTemplate()
        .queryForList(
            """
            SELECT set_config(budget.name,
                least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
                    budget.timeout_ms)::text,true)
            FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
                AS budget(name,timeout_ms)
            JOIN pg_settings settings ON settings.name=budget.name
            """);
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
