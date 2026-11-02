package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
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
import org.springframework.scheduling.annotation.Scheduled;

/**
 * WAITING 결제 요청을 주기적으로 처리한다. 한 건마다 별도 트랜잭션이며, 처리에 실패한 건은 서비스가 재시도 시각을 미뤄 기록하므로 다음 건을 이어 처리한다.
 *
 * <p>{@code concurrency}개 실행 슬롯이 각자 한 건씩 선점해 슬롯마다 최대 {@code batchSize}건을 처리한다. 선점은 {@code FOR
 * UPDATE SKIP LOCKED}와 임대로 나뉘고 같은 주문의 결제 요청·취소는 서비스가 주문 단위 advisory lock으로 직렬화하므로, 슬롯끼리 같은 요청을
 * 처리하거나 같은 주문을 동시에 바꾸지 않는다. 어느 슬롯이든 상한까지 처리했으면 남은 요청이 있다고 보고 간격을 기다리지 않고 이어서 처리한다.
 *
 * <p>선점이나 실패 기록 자체가 실패하면 그 슬롯을 멈추고, 다른 슬롯이 끝난 뒤 이번 주기를 예외로 끝낸다. 다음 주기에 다시 시도한다.
 */
public class PaymentRequestProcessor {
  private final ApplyOrderPaymentRequestService service;
  private final int batchSize;
  private final ExecutorService slots;
  private final List<Callable<Integer>> runs;
  private volatile boolean stopping;

  public PaymentRequestProcessor(ApplyOrderPaymentRequestService service, int batchSize) {
    this(service, batchSize, 1);
  }

  public PaymentRequestProcessor(
      ApplyOrderPaymentRequestService service, int batchSize, int concurrency) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Invalid payment request batch size");
    }
    if (concurrency < 1 || concurrency > 64) {
      throw new IllegalArgumentException("Payment request concurrency must be between 1 and 64");
    }
    this.service = service;
    this.batchSize = batchSize;
    AtomicInteger number = new AtomicInteger();
    this.slots =
        Executors.newFixedThreadPool(
            concurrency,
            task -> {
              Thread thread = new Thread(task, "payment-request-" + number.incrementAndGet());
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
      initialDelayString = "${app.payment-request-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-request-processor.interval-ms:1000}")
  public void run() {
    boolean more = true;
    while (more && !stopping) {
      more = false;
      RuntimeException failure = null;
      List<Future<Integer>> done;
      try {
        done = slots.invokeAll(runs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      for (Future<Integer> slot : done) {
        try {
          more |= slot.get() >= batchSize;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (ExecutionException e) {
          if (failure == null) {
            failure =
                e.getCause() instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(e.getCause());
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  /** 처리할 요청이 없거나 상한에 닿을 때까지 처리하고 처리한 건수를 돌려준다. */
  private int runSlot() {
    int processed = 0;
    while (processed < batchSize && !stopping) {
      if (!service.processNext()) {
        return processed;
      }
      processed++;
    }
    return processed;
  }

  /** 새 선점을 멈추고 진행 중인 처리가 끝나기를 잠시 기다린다. 끝나지 않은 요청은 임대 만료 뒤 다시 선점된다. */
  @PreDestroy
  void stop() throws InterruptedException {
    stopping = true;
    slots.shutdown();
    if (!slots.awaitTermination(10, TimeUnit.SECONDS)) {
      slots.shutdownNow();
    }
  }
}
