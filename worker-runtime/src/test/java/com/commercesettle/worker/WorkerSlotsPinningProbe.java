package com.commercesettle.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/** Runs in a two-carrier child JVM so an unrelated virtual task detects carrier pinning. */
public final class WorkerSlotsPinningProbe {
  private WorkerSlotsPinningProbe() {}

  public static void main(String[] args) throws Exception {
    require(Runtime.getRuntime().availableProcessors() == 2);
    require(Integer.getInteger("jdk.virtualThreadScheduler.parallelism", 2) == 2);
    boolean afterEmpty = Boolean.parseBoolean(args[0]);
    boolean progressed = run(afterEmpty);
    System.out.println("{\"progressed\":" + progressed + ",\"cleanupComplete\":true}");
    if (!progressed) System.exit(1);
  }

  private static boolean run(boolean afterEmpty) throws Exception {
    var entered = new CountDownLatch(2);
    var occupied = new CountDownLatch(afterEmpty ? 0 : 2);
    var dispatching = new CountDownLatch(2);
    var queuesHeld = new CountDownLatch(2);
    var releaseCallbacks = new CountDownLatch(1);
    var releaseWorkers = new CountDownLatch(1);
    var releaseQueues = new CountDownLatch(1);
    var callbacks = new AtomicReferenceArray<Thread>(2);
    var slots = new WorkerSlots[2];
    var executors = new ThreadPoolExecutor[2];
    var holders = new ArrayList<Thread>();
    Thread independentThread = null;
    try {
      for (int index = 0; index < 2; index++) {
        int current = index;
        var first = new AtomicBoolean(true);
        slots[index] =
            new WorkerSlots(
                "pinning-" + index,
                afterEmpty ? 1 : 2,
                1,
                () -> {
                  if (!first.getAndSet(false)) return false;
                  callbacks.set(current, Thread.currentThread());
                  entered.countDown();
                  await(releaseCallbacks);
                  dispatching.countDown();
                  return !afterEmpty;
                },
                exception -> {
                  throw new AssertionError(exception);
                });
        slots[index].wakeUp();
        var field = WorkerSlots.class.getDeclaredField("executor");
        field.setAccessible(true);
        executors[index] = (ThreadPoolExecutor) field.get(slots[index]);
        if (!afterEmpty)
          executors[index].execute(
              () -> {
                occupied.countDown();
                await(releaseWorkers);
              });
      }
      require(entered.await(5, TimeUnit.SECONDS));
      require(occupied.await(5, TimeUnit.SECONDS));
      for (var executor : executors) {
        var queue = executor.getQueue();
        queue.add(() -> {});
        holders.add(
            Thread.ofPlatform()
                .start(
                    () ->
                        queue.drainTo(
                            new ArrayList<Runnable>() {
                              @Override
                              public boolean add(Runnable task) {
                                queuesHeld.countDown();
                                await(releaseQueues);
                                return super.add(task);
                              }
                            })));
      }
      require(queuesHeld.await(5, TimeUnit.SECONDS));
      if (afterEmpty) for (var worker : slots) worker.wakeUp();
      releaseCallbacks.countDown();
      require(dispatching.await(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (callbacks.get(0).getState() != Thread.State.WAITING
          || callbacks.get(1).getState() != Thread.State.WAITING) {
        require(System.nanoTime() < deadline);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      var independent = new CountDownLatch(1);
      independentThread = Thread.ofVirtual().start(independent::countDown);
      return independent.await(2, TimeUnit.SECONDS);
    } finally {
      releaseCallbacks.countDown();
      releaseQueues.countDown();
      releaseWorkers.countDown();
      for (var holder : holders) require(holder.join(Duration.ofSeconds(5)));
      for (var worker : slots) if (worker != null) worker.close();
      for (var executor : executors) {
        if (executor != null) require(executor.awaitTermination(2, TimeUnit.SECONDS));
      }
      if (independentThread != null) require(independentThread.join(Duration.ofSeconds(2)));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      require(latch.await(30, TimeUnit.SECONDS));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new AssertionError("Probe synchronization did not complete");
  }
}
