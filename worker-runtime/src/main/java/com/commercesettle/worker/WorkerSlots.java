package com.commercesettle.worker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** DB에 저장된 작업을 슬롯마다 독립적으로 처리한다. 메모리에는 작업 대신 합쳐진 깨움 신호만 둔다. */
public final class WorkerSlots implements AutoCloseable {
  private final BooleanSupplier next;
  private final Consumer<RuntimeException> failure;
  private final int batchSize;
  private final ThreadPoolExecutor executor;
  private final Slot[] slots;
  // pending, idle 및 슬롯 상태는 실행기 모니터로 함께 보호한다.
  private final ArrayDeque<Slot> idle = new ArrayDeque<>();
  // 모든 슬롯이 바쁠 때도 슬롯 수만큼의 배정 수요를 유지하고 그 이상의 신호는 합친다.
  private int pending;
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
    for (int i = 0; i < concurrency; i++) {
      slots[i] = new Slot();
      idle.addLast(slots[i]);
    }
  }

  /** 신호 한 건은 가용 슬롯 하나를 깨운다. 모두 바쁘면 신호를 대기열에 남겨 다음 가용 슬롯에 넘긴다. */
  public void wakeUp() {
    RuntimeException rejected;
    synchronized (this) {
      if (stopping) return;
      pending = Math.min(slots.length, pending + 1);
      rejected = dispatchPending();
    }
    report(rejected);
  }

  /** 기동·주기 조회는 오류 슬롯을 재개하고 모든 가용 슬롯으로 누락 신호·도래한 재시도를 회수한다. */
  public void recover() {
    List<RuntimeException> rejected = new ArrayList<>();
    synchronized (this) {
      if (stopping) return;
      pending = slots.length;
      for (Slot slot : slots) {
        if (slot.paused) {
          slot.paused = false;
          if (!slot.running) idle.addLast(slot);
        }
      }
      while (!idle.isEmpty()) {
        pending--;
        RuntimeException error = start(idle.removeFirst());
        if (error != null) rejected.add(error);
      }
    }
    rejected.forEach(this::report);
  }

  private RuntimeException dispatchPending() {
    if (stopping || pending == 0 || idle.isEmpty()) return null;
    pending--;
    return start(idle.removeFirst());
  }

  private RuntimeException start(Slot slot) {
    slot.running = true;
    try {
      executor.execute(slot);
      return null;
    } catch (RejectedExecutionException exception) {
      slot.running = false;
      slot.paused = true;
      pending = Math.min(slots.length, pending + 1);
      return exception;
    }
  }

  private void report(RuntimeException error) {
    if (error != null) failure.accept(error);
  }

  @Override
  public void close() throws InterruptedException {
    synchronized (this) {
      stopping = true;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException exception) {
      executor.shutdownNow();
      throw exception;
    }
  }

  private final class Slot implements Runnable {
    private boolean running;
    private boolean paused;

    @Override
    public void run() {
      try {
        drain();
      } catch (RuntimeException exception) {
        pause();
        failure.accept(exception);
      } catch (Error error) {
        pause();
        throw error;
      } finally {
        RuntimeException rejected;
        synchronized (WorkerSlots.this) {
          running = false;
          if (!stopping && !paused) idle.addLast(this);
          // 마지막 빈 조회 뒤 들어온 신호는 먼저 비어 있던 슬롯에도 인계할 수 있다.
          rejected = dispatchPending();
        }
        report(rejected);
      }
    }

    private void pause() {
      synchronized (WorkerSlots.this) {
        paused = true;
        pending = Math.min(slots.length, pending + 1);
      }
    }

    private void drain() {
      while (!stopping) {
        // 실행 중 슬롯의 조회는 다른 가용 슬롯에 넘길 신호를 소비하지 않는다.
        for (int i = 0; i < batchSize; i++) {
          if (stopping || !next.getAsBoolean()) return;
        }
      }
    }
  }
}
