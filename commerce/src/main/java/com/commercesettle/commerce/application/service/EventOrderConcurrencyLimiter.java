package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.OrderCapacityExceededException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 신규 선착순 이벤트 주문의 이벤트별 동시 실행 상한. 같은 이벤트 행 잠금을 기다리는 주문이 DB 커넥션을 점유해 일반 주문까지 늦추지 않도록, 주문 트랜잭션을 시작하기 전에
 * 인스턴스 안에서 슬롯을 받는다. 상한은 인스턴스별이며 여러 인스턴스면 합산된다.
 */
public class EventOrderConcurrencyLimiter {
  private final int maxConcurrent;
  private final Duration maxWait;
  private final long retryAfterSeconds;
  private final Map<UUID, Semaphore> slots = new ConcurrentHashMap<>();

  public EventOrderConcurrencyLimiter(int maxConcurrent, Duration maxWait) {
    if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be positive");
    if (maxWait.isNegative()) throw new IllegalArgumentException("maxWait must not be negative");
    this.maxConcurrent = maxConcurrent;
    this.maxWait = maxWait;
    // 대기 상한만큼 뒤에 다시 시도하도록 안내한다. 30초 접수 제한과 달리 짧은 경합이다.
    this.retryAfterSeconds = Math.max(1, (maxWait.toMillis() + 999) / 1000);
  }

  /**
   * 슬롯을 받을 때까지 최대 대기 상한만큼 기다린다. 받지 못하면 {@link OrderCapacityExceededException}을 던진다. 반환한 슬롯은 주문
   * 트랜잭션이 끝난 뒤 닫아야 한다.
   */
  public Permit acquire(UUID eventId) {
    Semaphore semaphore = slots.computeIfAbsent(eventId, id -> new Semaphore(maxConcurrent, true));
    boolean acquired;
    try {
      acquired = semaphore.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) throw new OrderCapacityExceededException(retryAfterSeconds);
    return new Permit(semaphore);
  }

  /** 슬롯 반납. 여러 번 닫아도 한 번만 반납한다. */
  public static final class Permit implements AutoCloseable {
    private Semaphore semaphore;

    private Permit(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    @Override
    public void close() {
      if (semaphore == null) return;
      semaphore.release();
      semaphore = null;
    }
  }
}
