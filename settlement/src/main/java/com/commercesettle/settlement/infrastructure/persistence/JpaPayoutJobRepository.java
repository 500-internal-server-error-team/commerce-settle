package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutJobStatus;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 지급 작업 임대는 업무 상태 전이가 아니라 실행권 선점이라 조건부 SQL로 처리하고, 만료는 DB 시각으로 판정한다. */
@Repository
class JpaPayoutJobRepository implements PayoutJobRepository {
  private final SpringDataPayoutJobRepository repository;
  private final NamedParameterJdbcOperations jdbc;

  JpaPayoutJobRepository(
      SpringDataPayoutJobRepository repository, NamedParameterJdbcOperations jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Override
  public PayoutJob save(PayoutJob job) {
    return repository.save(job);
  }

  @Override
  public Optional<PayoutJob> findByPayoutId(UUID payoutId) {
    return repository.findByPayoutId(payoutId);
  }

  @Override
  @Transactional
  public Optional<LeasedJob> leaseNext(String owner, Duration leaseDuration) {
    return jdbc
        .query(
            "UPDATE settlement.payout_jobs"
                + " SET status = 'RUNNING', lease_owner = :owner, lease_token = :token,"
                + " lease_until = now() + make_interval(secs => :leaseSeconds),"
                + " attempts = attempts + 1, updated_at = now(), version = version + 1"
                + " WHERE id = ("
                + "   SELECT id FROM settlement.payout_jobs"
                + "   WHERE (status IN ('PENDING', 'RETRY') AND next_run_at <= now())"
                + "      OR (status = 'RUNNING' AND lease_until < now())"
                + "   ORDER BY next_run_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                + " RETURNING id, payout_id, business_key, lease_token, attempts",
            new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("token", UUID.randomUUID())
                .addValue("leaseSeconds", seconds(leaseDuration)),
            (rs, row) ->
                new LeasedJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("payout_id", UUID.class),
                    rs.getObject("business_key", UUID.class),
                    rs.getObject("lease_token", UUID.class),
                    rs.getInt("attempts")))
        .stream()
        .findFirst();
  }

  @Override
  public boolean holdsLease(UUID jobId, UUID leaseToken) {
    Boolean holds =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM settlement.payout_jobs WHERE id = :id"
                + " AND status = 'RUNNING' AND lease_token = :token AND lease_until > now())",
            new MapSqlParameterSource().addValue("id", jobId).addValue("token", leaseToken),
            Boolean.class);
    return Boolean.TRUE.equals(holds);
  }

  @Override
  public boolean releaseLease(
      UUID jobId, UUID leaseToken, PayoutJobStatus status, Duration runAfter) {
    return jdbc.update(
            "UPDATE settlement.payout_jobs"
                + " SET status = :status, next_run_at = now() + make_interval(secs => :runAfter),"
                + " lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = now(),"
                + " version = version + 1"
                + " WHERE id = :id AND status = 'RUNNING' AND lease_token = :token"
                + " AND lease_until > now()",
            new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("token", leaseToken)
                .addValue("status", status.name())
                .addValue("runAfter", seconds(runAfter)))
        == 1;
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
