package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 작업 선점이 대기·만료 임대 작업을 next_run_at 순서로 고르고 종료 이력을 훑지 않는지 확인한다. */
@Testcontainers
@Timeout(120)
class PaymentJobLeaseIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  private JdbcTemplate jdbc;
  private JpaPaymentJobRepository jobs;
  private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    DataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    jobs = new JpaPaymentJobRepository(null, new NamedParameterJdbcTemplate(jdbc));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    jdbc.update("DELETE FROM payment.payment_jobs");
  }

  @Test
  @DisplayName("만료 임대·재시도·대기 작업을 next_run_at 순서로 고르고 유효 임대·미래·다른 종류는 건너뛴다")
  void leasesDueJobsInNextRunOrder() {
    UUID expired = insert("APPROVE", "RUNNING", "-10 minutes", "-1 second");
    UUID retry = insert("APPROVE", "RETRY", "-5 minutes", null);
    insert("APPROVE", "RUNNING", "-20 minutes", "+1 minute");
    insert("APPROVE", "PENDING", "+1 minute", null);
    insert("REFUND", "PENDING", "-30 minutes", null);
    insert("APPROVE", "DONE", "-40 minutes", null);

    assertThat(lease()).map(LeasedJob::jobId).contains(expired);
    assertThat(lease()).map(LeasedJob::jobId).contains(retry);
    assertThat(lease()).isEmpty();
  }

  @Test
  @DisplayName("종료 작업이 많아도 선점은 테이블 전체를 훑지 않는다")
  void leaseSkipsFinishedHistory() {
    jdbc.update(
        """
        INSERT INTO payment.payment_jobs (id,payment_id,operation,business_key,status,attempts,
            next_run_at,created_at,updated_at,version)
        SELECT gen_random_uuid(),gen_random_uuid(),'APPROVE',gen_random_uuid(),'DONE',1,
            now() - interval '1 hour',now(),now(),1
        FROM generate_series(1, 20000)
        """);
    UUID due = insert("APPROVE", "PENDING", "-1 second", null);
    jdbc.execute("ANALYZE payment.payment_jobs");

    var result =
        transactions.execute(
            status ->
                Map.of(
                    "job",
                    lease(),
                    "scans",
                    jdbc.queryForList(
                        """
                        SELECT CASE c.relkind WHEN 'r' THEN 'table' ELSE c.relname END AS name,
                            pg_stat_get_xact_numscans(c.oid) AS scans
                        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname='payment' AND (c.relname='payment_jobs' OR c.oid IN (
                            SELECT indexrelid FROM pg_index
                            WHERE indrelid='payment.payment_jobs'::regclass))
                        """)));

    assertThat((Optional<?>) result.get("job"))
        .get()
        .extracting(job -> ((LeasedJob) job).jobId())
        .isEqualTo(due);
    @SuppressWarnings("unchecked")
    var scans = (List<Map<String, Object>>) result.get("scans");
    assertThat(count(scans, "table")).isZero();
    assertThat(count(scans, "ix_payment_jobs_lease")).isPositive();
  }

  private Optional<LeasedJob> lease() {
    return jobs.leaseNext(PaymentJobOperation.APPROVE, "worker", Duration.ofSeconds(30));
  }

  private UUID insert(String operation, String status, String due, String lease) {
    UUID id = UUID.randomUUID();
    boolean leased = lease != null;
    jdbc.update(
        """
        INSERT INTO payment.payment_jobs (id,payment_id,operation,business_key,status,attempts,
            next_run_at,lease_owner,lease_token,lease_until,created_at,updated_at,version)
        VALUES (?,gen_random_uuid(),?,gen_random_uuid(),?,0,now() + CAST(? AS interval),
            CASE WHEN ? THEN 'other' END,CASE WHEN ? THEN gen_random_uuid() END,
            now() + CAST(? AS interval),now(),now(),0)
        """,
        id,
        operation,
        status,
        due,
        leased,
        leased,
        lease);
    return id;
  }

  private static long count(List<Map<String, Object>> scans, String name) {
    return scans.stream()
        .filter(row -> name.equals(row.get("name")))
        .mapToLong(row -> ((Number) row.get("scans")).longValue())
        .sum();
  }
}
