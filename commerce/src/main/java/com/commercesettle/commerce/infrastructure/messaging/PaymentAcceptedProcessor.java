package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.commercesettle.observability.RepeatedWarning;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 한 건씩 반영·롤백·재시도 기록 뒤 다음 도래 건을 처리한다. */
public class PaymentAcceptedProcessor {
  /**
   * 한 실행이 스케줄러 스레드를 점유하는 기본 상한. 넘으면 다음 주기에 이어서 처리한다. 스케줄러 스레드는 다른 예약 작업과 함께 쓰므로 {@code
   * spring.task.scheduling.pool.size}를 처리기 수보다 크게 둔다.
   */
  static final long MAX_RUN_NANOS = 10_000_000_000L;

  private static final Logger LOG = LoggerFactory.getLogger(PaymentAcceptedProcessor.class);
  private final PaymentAcceptedService service;
  private final int batchSize;
  private final long maxRunNanos;
  private final RepeatedWarning paused = new RepeatedWarning();

  public PaymentAcceptedProcessor(PaymentAcceptedService service, int batchSize) {
    this(service, batchSize, MAX_RUN_NANOS);
  }

  public PaymentAcceptedProcessor(PaymentAcceptedService service, int batchSize, long maxRunNanos) {
    if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid batch size");
    if (maxRunNanos < 1) throw new IllegalArgumentException("Invalid max run");
    this.service = service;
    this.batchSize = batchSize;
    this.maxRunNanos = maxRunNanos;
  }

  @Scheduled(
      initialDelayString = "${app.payment-accepted-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-accepted-processor.interval-ms:1000}")
  public void run() {
    try {
      drain(service::processNext, batchSize, maxRunNanos);
    } catch (RuntimeException exception) {
      paused.at(LOG).log("Payment acceptance processing paused: errorCode=INBOX_UNAVAILABLE");
    }
  }

  /**
   * 대기 건이 없을 때까지 한 건씩 처리한다. 묶음을 가득 채우면 다음 주기를 기다리지 않고 이어서 처리하되, 실행 시간이 상한(기본 {@link
   * #MAX_RUN_NANOS})을 넘으면 묶음 경계에서 멈춘다. 각 건의 트랜잭션·실패 격리는 호출하는 처리기가 맡는다.
   */
  public static void drain(BooleanSupplier next, int batchSize) {
    drain(next, batchSize, MAX_RUN_NANOS);
  }

  public static void drain(BooleanSupplier next, int batchSize, long maxRunNanos) {
    long started = System.nanoTime();
    do {
      for (int i = 0; i < batchSize; i++) {
        if (!next.getAsBoolean()) return;
      }
    } while (System.nanoTime() - started < maxRunNanos);
  }
}
