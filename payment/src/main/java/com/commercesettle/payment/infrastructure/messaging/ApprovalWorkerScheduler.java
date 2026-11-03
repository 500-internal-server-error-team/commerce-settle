package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.service.ApprovalWorker;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 Worker 폴링. Worker 역할에서 명시적으로 켰을 때만 동작한다.
 *
 * <p>한 번의 폴링에서 {@code concurrency}개(기본 6) 실행 슬롯이 각자 도래한 작업을 한 건씩 임대해 최대 {@code
 * max-jobs-per-poll}건까지 실행한다. 임대는 DB의 {@code FOR UPDATE SKIP LOCKED}와 임대 token으로 나뉘므로 슬롯끼리 같은 작업을
 * 실행하지 않는다. 외부 호출 동안 DB 연결을 쥐지 않아 슬롯 수만큼 PG 대역 호출이 겹친다. 어느 슬롯이든 상한까지 처리했으면 남은 작업이 있다고 보고 간격을 기다리지
 * 않고 다시 폴링한다.
 *
 * <p>작업 오류가 나면 다른 슬롯의 진행 중 묶음이 끝난 뒤 이번 폴링을 끝내고 다음 예약 주기에 다시 시도한다. 이미 임대한 작업은 임대 만료 뒤 회수된다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "worker")
@ConditionalOnProperty(name = "app.payment-approval.enabled", havingValue = "true")
class ApprovalWorkerScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(ApprovalWorkerScheduler.class);

  private final ApprovalWorker worker;
  private final int maxJobsPerPoll;
  private final ExecutorService slots;
  private final List<Callable<Integer>> runs;
  private volatile boolean stopping;

  ApprovalWorkerScheduler(
      ApprovalWorker worker,
      @Value("${app.payment-approval.max-jobs-per-poll:20}") int maxJobsPerPoll,
      @Value("${app.payment-approval.concurrency:6}") int concurrency) {
    if (maxJobsPerPoll < 1) {
      throw new IllegalArgumentException("Invalid approval jobs per poll");
    }
    if (concurrency < 1 || concurrency > 64) {
      throw new IllegalArgumentException("Approval concurrency must be between 1 and 64");
    }
    this.worker = worker;
    this.maxJobsPerPoll = maxJobsPerPoll;
    AtomicInteger number = new AtomicInteger();
    this.slots =
        Executors.newFixedThreadPool(
            concurrency,
            task -> {
              Thread thread = new Thread(task, "payment-approval-" + number.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    List<Callable<Integer>> list = new ArrayList<>();
    for (int i = 0; i < concurrency; i++) {
      list.add(this::runSlot);
    }
    this.runs = List.copyOf(list);
  }

  @Scheduled(
      initialDelayString = "${app.payment-approval.poll-interval-ms:1000}",
      fixedDelayString = "${app.payment-approval.poll-interval-ms:1000}")
  void poll() {
    boolean more = true;
    while (more && !stopping) {
      more = false;
      try {
        for (Future<Integer> done : slots.invokeAll(runs)) {
          more |= done.get() >= maxJobsPerPoll;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException e) {
        // 다른 슬롯이 상한을 채웠어도 오류 뒤에는 다음 예약 주기까지 기다린다.
        LOG.atError()
            .setCause(e.getCause())
            .addKeyValue("event", "payment.approval")
            .addKeyValue("result", "ERROR")
            .log("Approval polling stopped after a job error");
        return;
      }
    }
  }

  /** 도래한 작업이 없거나 상한에 닿을 때까지 실행하고 실행한 건수를 돌려준다. */
  private int runSlot() {
    int processed = 0;
    while (processed < maxJobsPerPoll && !stopping) {
      if (!worker.runOnce()) {
        return processed;
      }
      processed++;
    }
    return processed;
  }

  /** 새 작업 임대를 멈추고 진행 중인 호출이 끝나기를 잠시 기다린다. 끝나지 않은 작업은 임대 만료 뒤 다른 실행자가 회수한다. */
  @PreDestroy
  void stop() throws InterruptedException {
    stopping = true;
    slots.shutdown();
    if (!slots.awaitTermination(10, TimeUnit.SECONDS)) {
      slots.shutdownNow();
    }
  }
}
