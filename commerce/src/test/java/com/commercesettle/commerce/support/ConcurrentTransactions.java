package com.commercesettle.commerce.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 DB 통합 테스트에서 동시 요청과 다른 트랜잭션의 잠금 유지를 재현한다. */
public final class ConcurrentTransactions {
  private ConcurrentTransactions() {}

  /** 모든 작업을 동시에 시작하고 각 결과값 또는 발생한 예외를 모은다. */
  public static List<Object> runConcurrently(int count, Callable<Object> task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(count);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    return task.call();
                  } catch (RuntimeException e) {
                    return e;
                  }
                }));
      }
      start.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<Object> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  /** 다른 스레드의 트랜잭션에서 작업을 실행하고, 닫을 때 커밋할 때까지 잠금을 유지한다. */
  public static HeldTransaction holdTransaction(TransactionTemplate tx, Runnable work)
      throws Exception {
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> committed =
        executor.submit(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      work.run();
                      acquired.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                      }
                    }));
    if (!acquired.await(10, TimeUnit.SECONDS)) {
      committed.get(1, TimeUnit.SECONDS);
      throw new IllegalStateException("holding transaction did not start");
    }
    return new HeldTransaction(release, committed, executor);
  }

  public record HeldTransaction(
      CountDownLatch release, Future<?> committed, ExecutorService executor)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      release.countDown();
      try {
        committed.get(10, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
