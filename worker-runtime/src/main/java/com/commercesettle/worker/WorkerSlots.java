package com.commercesettle.worker;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** DB에 저장된 작업을 슬롯마다 독립적으로 처리한다. 메모리에는 작업 대신 합쳐진 깨움 신호만 둔다. */
public final class WorkerSlots implements AutoCloseable {
  private final BooleanSupplier next;
  private final Consumer<RuntimeException> failure;
  private final int batchSize;
  private final ThreadPoolExecutor executor;
  private final Slot[] slots;
  private final AtomicInteger cursor = new AtomicInteger();
  private volatile boolean stopping;

  public WorkerSlots(
      String name,
      int concurrency,
      int batchSize,
      BooleanSupplier next,
      Consumer<RuntimeException> failure) {
    if (concurrency < 1 || concurrency > 1024 || batchSize < 1) {
      throw new IllegalArgumentException("Invalid worker limits");
    }
    this.next = Objects.requireNonNull(next);
    this.failure = Objects.requireNonNull(failure);
    this.batchSize = batchSize;
    executor =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(concurrency),
            Thread.ofVirtual().name(name + "-", 0).factory());
    slots = new Slot[concurrency];
    for (int i = 0; i < concurrency; i++) slots[i] = new Slot();
  }

  /** 한 건의 저장 신호로 모든 슬롯에서 빈 DB 조회를 하지 않는다. 실행 중 신호는 슬롯 안에서 합친다. */
  public void wakeUp() {
    slots[Math.floorMod(cursor.getAndIncrement(), slots.length)].signal(false);
  }

  /** 기동·주기 조회는 모든 슬롯을 깨워 유실 신호·다른 인스턴스·도래한 재시도를 회수한다. */
  public void recover() {
    for (Slot slot : slots) slot.signal(true);
  }

  @Override
  public void close() throws InterruptedException {
    stopping = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException exception) {
      executor.shutdownNow();
      throw exception;
    }
  }

  private final class Slot implements Runnable {
    private boolean requested;
    private boolean running;
    private boolean paused;

    synchronized void signal(boolean recovery) {
      if (stopping) return;
      requested = true;
      if (recovery) paused = false;
      if (!running && !paused) schedule();
    }

    private void schedule() {
      running = true;
      try {
        executor.execute(this);
      } catch (RejectedExecutionException exception) {
        running = false;
        paused = true;
        if (!stopping) failure.accept(exception);
      }
    }

    @Override
    public void run() {
      try {
        drain();
      } catch (RuntimeException exception) {
        synchronized (this) {
          paused = true;
        }
        failure.accept(exception);
      } catch (Error error) {
        synchronized (this) {
          paused = true;
        }
        throw error;
      } finally {
        synchronized (this) {
          running = false;
          // 마지막 빈 조회와 종료 사이에 들어온 신호도 놓치지 않는다.
          if (!stopping && !paused && requested) schedule();
        }
      }
    }

    private void drain() {
      while (!stopping) {
        synchronized (this) {
          requested = false;
        }
        boolean full = true;
        for (int i = 0; i < batchSize; i++) {
          if (stopping || !next.getAsBoolean()) {
            full = false;
            break;
          }
        }
        synchronized (this) {
          if (!full && !requested) return;
        }
      }
    }
  }
}
