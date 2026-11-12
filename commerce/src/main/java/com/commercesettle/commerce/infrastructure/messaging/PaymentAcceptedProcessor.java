package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 한 건씩 반영·롤백·재시도 기록 뒤 다음 도래 건을 처리한다. */
public class PaymentAcceptedProcessor {
  /** 한 실행이 공유 스케줄러 스레드를 점유하는 상한. 넘으면 다음 주기에 이어서 처리한다. */
  static final long MAX_RUN_NANOS = 1_000_000_000L;

  private static final Logger LOG = LoggerFactory.getLogger(PaymentAcceptedProcessor.class);
  private final PaymentAcceptedService service;
  private final int batchSize;

  public PaymentAcceptedProcessor(PaymentAcceptedService service, int batchSize) {
    if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid batch size");
    this.service = service;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.payment-accepted-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-accepted-processor.interval-ms:1000}")
  public void run() {
    try {
      drain(service::processNext, batchSize);
    } catch (RuntimeException exception) {
      LOG.warn("Payment acceptance processing paused: errorCode=INBOX_UNAVAILABLE");
    }
  }

  /**
   * 대기 건이 없을 때까지 한 건씩 처리한다. 묶음을 가득 채우면 다음 주기를 기다리지 않고 이어서 처리하되, 실행 시간이 {@link #MAX_RUN_NANOS}를 넘으면
   * 묶음 경계에서 멈춘다. 각 건의 트랜잭션·실패 격리는 호출하는 처리기가 맡는다.
   */
  public static void drain(BooleanSupplier next, int batchSize) {
    long started = System.nanoTime();
    do {
      for (int i = 0; i < batchSize; i++) {
        if (!next.getAsBoolean()) return;
      }
    } while (System.nanoTime() - started < MAX_RUN_NANOS);
  }
}
