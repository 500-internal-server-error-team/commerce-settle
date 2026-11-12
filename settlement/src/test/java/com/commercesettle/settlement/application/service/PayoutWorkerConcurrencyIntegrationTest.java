package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.commercesettle.settlement.support.FixedProductSettlementContexts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실행자 둘이 같은 DB의 지급 작업을 동시에 처리할 때 중복 지급과 늦은 결과 반영이 없는지 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Import(FixedProductSettlementContexts.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PayoutWorkerConcurrencyIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final LocalDate BATCH_DATE = LocalDate.of(2026, 10, 1);
  private static final Instant SUCCEEDED_AT = Instant.parse("2026-10-01T01:30:00Z");

  @Autowired SellerSettlementService sellers;
  @Autowired SettlementItemRepository items;
  @Autowired PayoutJobRepository jobs;
  @Autowired PayoutResultRecorder recorder;
  @Autowired PayoutWorkerSettings settings;
  @Autowired JdbcTemplate jdbc;
  @Autowired CountingGateway gateway;

  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void cleanUp() {
    gateway.release();
    pool.shutdownNow();
    for (String table :
        List.of(
            "settlement_items",
            "settlement_batches",
            "payouts",
            "payout_jobs",
            "settlement_reservations")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
    gateway.reset();
  }

  @Test
  @DisplayName("실행자 둘이 8개씩 동시에 처리해도 업무 키마다 지급 실행 요청은 한 번이고 모든 지급·배치가 한 번씩 완료된다")
  void twoExecutorsNeverPayTwice() throws Exception {
    int payouts = 24;
    for (int i = 0; i < payouts; i++) settle(UUID.randomUUID());
    gateway.release();
    List<PayoutWorker> workers = List.of(newWorker(), newWorker());

    List<Future<?>> runs = new ArrayList<>();
    for (PayoutWorker worker : workers) {
      for (int slot = 0; slot < settings.maxConcurrency(); slot++) {
        runs.add(
            pool.submit(
                () -> {
                  while (worker.runOnce()) {}
                }));
      }
    }
    for (Future<?> run : runs) run.get(60, TimeUnit.SECONDS);

    assertThat(gateway.executed)
        .hasSize(payouts)
        .allSatisfy((key, count) -> assertThat(count).hasValue(1));
    assertThat(gateway.lookedUp).isEmpty();
    assertThat(count("payout_jobs WHERE status = 'DONE'")).isEqualTo(payouts);
    assertThat(count("payouts WHERE status = 'SUCCEEDED'")).isEqualTo(payouts);
    assertThat(count("settlement_batches WHERE status = 'COMPLETED'")).isEqualTo(payouts);
    assertThat(count("settlement_items WHERE status <> 'PAID'")).isZero();
  }

  @Test
  @DisplayName("실행자 A의 호출이 임대 시간을 넘기면 실행자 B가 복구로 회수해 조회부터 하고, A의 늦은 결과는 반영하지 않는다")
  void expiredLeaseIsRecoveredByAnotherExecutor() throws Exception {
    settle(UUID.randomUUID());
    PayoutWorker a = newWorker();
    PayoutWorker b = newWorker();

    Future<Boolean> first = pool.submit(a::runOnce);
    assertThat(gateway.callStarted.await(10, TimeUnit.SECONDS)).isTrue();
    jdbc.update("UPDATE settlement.payout_jobs SET lease_until = now() - interval '1 second'");

    var recovered = b.lease(PayoutJobRepository.LeaseKind.RECOVERY);
    assertThat(recovered).isPresent();
    b.run(recovered.get());
    gateway.release();

    // A의 반영은 임대 상실이나 이미 끝난 지급의 상태 전이 거절로 롤백된다. 어느 쪽이든 결과가 바뀌지 않아야 한다.
    catchThrowable(() -> first.get(10, TimeUnit.SECONDS));
    assertThat(first).isDone();
    assertThat(gateway.executed).hasSize(1);
    assertThat(gateway.lookedUp).hasSize(1);
    Map<String, Object> payout = jdbc.queryForMap("SELECT * FROM settlement.payouts");
    assertThat(payout)
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("external_ref", gateway.lookupRef);
    assertThat(jdbc.queryForMap("SELECT * FROM settlement.payout_jobs"))
        .containsEntry("status", "DONE")
        .containsEntry("attempts", 2);
  }

  private PayoutWorker newWorker() {
    return new PayoutWorker(jobs, recorder, gateway, settings);
  }

  private void settle(UUID seller) {
    Instant readyAt = SettlementBatch.cutoffOf(BATCH_DATE).minusSeconds(60);
    long base = 10_000L;
    long fee = base * 15 / 100;
    SettlementItem item =
        SettlementItem.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            seller,
            new SettlementAmounts(base, 0, base, base, 0, fee, base - fee),
            0,
            readyAt.minusSeconds(600));
    item.markPayable(readyAt, readyAt, readyAt);
    items.save(item);
    sellers.settle(seller, BATCH_DATE).orElseThrow();
  }

  private int count(String fromWhere) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + fromWhere, Integer.class);
  }

  /** 업무 키별 호출 수를 세고, 풀어 주기 전까지 실행 요청을 붙잡는다. 조회는 붙잡지 않고 성공을 돌려준다. */
  static class CountingGateway implements PayoutGateway {
    final Map<UUID, AtomicInteger> executed = new ConcurrentHashMap<>();
    final Map<UUID, AtomicInteger> lookedUp = new ConcurrentHashMap<>();
    final UUID lookupRef = UUID.randomUUID();
    volatile CountDownLatch callStarted = new CountDownLatch(1);
    volatile CountDownLatch hold = new CountDownLatch(1);

    @Override
    public Result execute(PayoutRequest request) {
      executed.computeIfAbsent(request.businessKey(), key -> new AtomicInteger()).incrementAndGet();
      callStarted.countDown();
      try {
        if (!hold.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return new Result.Succeeded(UUID.randomUUID(), SUCCEEDED_AT);
    }

    @Override
    public Result lookup(PayoutRequest request) {
      lookedUp.computeIfAbsent(request.businessKey(), key -> new AtomicInteger()).incrementAndGet();
      return new Result.Succeeded(lookupRef, SUCCEEDED_AT);
    }

    void release() {
      hold.countDown();
    }

    void reset() {
      executed.clear();
      lookedUp.clear();
      callStarted = new CountDownLatch(1);
      hold = new CountDownLatch(1);
    }
  }

  @TestConfiguration
  static class Gateway {
    @Bean
    @Primary
    CountingGateway countingGateway() {
      return new CountingGateway();
    }
  }
}
