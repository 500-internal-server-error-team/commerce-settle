package com.commercesettle.observability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * 같은 원인으로 반복되는 일시 장애 경고를 창마다 한 번만 WARN으로 남긴다. 창 안의 나머지는 DEBUG로 내리고, 다음 WARN에 그동안 내린 건수를
 * `suppressed`로 붙인다. 발생 건수는 지표로 센다. 호출 위치마다 하나씩 두며 서로 다른 원인을 한 인스턴스로 묶지 않는다.
 */
public final class RepeatedWarning {
  private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

  private final long windowNanos;
  private final LongSupplier nanoTime;
  private final AtomicLong nextWarnAt;
  private final AtomicLong suppressed = new AtomicLong();

  public RepeatedWarning() {
    this(DEFAULT_WINDOW, System::nanoTime);
  }

  RepeatedWarning(Duration window, LongSupplier nanoTime) {
    this.windowNanos = window.toNanos();
    this.nanoTime = nanoTime;
    this.nextWarnAt = new AtomicLong(nanoTime.getAsLong());
  }

  /** 이번 경고를 남길 등급의 빌더. 창이 열렸으면 WARN, 아니면 DEBUG다. */
  public LoggingEventBuilder at(Logger logger) {
    long now = nanoTime.getAsLong();
    long next = nextWarnAt.get();
    if (now - next >= 0 && nextWarnAt.compareAndSet(next, now + windowNanos)) {
      long skipped = suppressed.getAndSet(0);
      var warn = logger.atWarn();
      return skipped == 0 ? warn : warn.addKeyValue("suppressed", skipped);
    }
    suppressed.incrementAndGet();
    return logger.atDebug();
  }
}
