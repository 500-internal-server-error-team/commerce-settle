package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.service.PayoutWorker;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeaseKind;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeasedJob;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지급 Worker 디스패처. 정해진 간격마다 빈 슬롯 수만큼만 작업을 임대해 실행 풀에 넘긴다(ADR 0005). 주말·공휴일도 같다. 결과 조회·중단 복구를 먼저 빈
 * 슬롯까지 임대하고, 새 지급은 복구 전용 슬롯을 남긴 나머지에서만 임대한다. 임대는 실행 직전에 해서 대기열에서 임대 시간을 쓰지 않는다. 한 작업의 예외는 기록하고 슬롯을
 * 돌려준다. 그 작업은 임대 만료 뒤 복구로 다시 임대되고 시도 상한에서 운영 확인으로 넘어간다.
 */
@Component
@ConditionalOnProperty(
    prefix = "settlement.payout-worker",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class PayoutWorkerScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(PayoutWorkerScheduler.class);

  private final PayoutWorker worker;
  private final Executor executor;
  private final int maxConcurrency;
  private final int newSlots;
  // 늘리는 쪽은 폴링 스레드 하나뿐이라 확인 후 증가가 상한을 넘지 않는다. 줄이는 쪽은 실행 스레드다.
  private final AtomicInteger running = new AtomicInteger();
  private final AtomicInteger runningNew = new AtomicInteger();

  PayoutWorkerScheduler(
      PayoutWorker worker,
      @Qualifier("payoutWorkerExecutor") Executor executor,
      PayoutWorkerSettings settings) {
    this.worker = worker;
    this.executor = executor;
    this.maxConcurrency = settings.maxConcurrency();
    this.newSlots = settings.maxConcurrency() - settings.recoverySlots();
  }

  @Scheduled(fixedDelayString = "${settlement.payout-worker.poll-interval-ms}")
  void poll() {
    while (running.get() < maxConcurrency && dispatch(LeaseKind.RECOVERY)) {}
    while (running.get() < maxConcurrency
        && runningNew.get() < newSlots
        && dispatch(LeaseKind.NEW)) {}
  }

  private boolean dispatch(LeaseKind kind) {
    Optional<LeasedJob> leased;
    try {
      leased = worker.lease(kind);
    } catch (RuntimeException e) {
      logFailure(e, "Payout job lease failed; it will be retried at the next poll");
      return false;
    }
    if (leased.isEmpty()) {
      return false;
    }
    LeasedJob job = leased.get();
    acquire(kind);
    try {
      executor.execute(() -> runAndRelease(job, kind));
    } catch (RejectedExecutionException e) {
      release(kind);
      logFailure(e, "Payout job was not started; it will be recovered after its lease expires");
      return false;
    }
    return true;
  }

  private void runAndRelease(LeasedJob job, LeaseKind kind) {
    try {
      worker.run(job);
    } catch (RuntimeException e) {
      logFailure(e, "Payout job failed; it will be retried after its lease expires");
    } finally {
      release(kind);
    }
  }

  private void acquire(LeaseKind kind) {
    running.incrementAndGet();
    if (kind == LeaseKind.NEW) runningNew.incrementAndGet();
  }

  private void release(LeaseKind kind) {
    if (kind == LeaseKind.NEW) runningNew.decrementAndGet();
    running.decrementAndGet();
  }

  private static void logFailure(RuntimeException e, String message) {
    LOGGER
        .atError()
        .setCause(e)
        .addKeyValue("event", "settlement.payout")
        .addKeyValue("result", "ERROR")
        .addKeyValue("errorType", e.getClass().getName())
        .log(message);
  }
}
