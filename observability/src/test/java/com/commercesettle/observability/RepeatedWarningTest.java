package com.commercesettle.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RepeatedWarningTest {
  private final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RepeatedWarningTest.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final AtomicLong now = new AtomicLong(1_000);
  private final RepeatedWarning warning = new RepeatedWarning(Duration.ofNanos(100), now::get);

  @BeforeEach
  void attach() {
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(logs);
    logger.setLevel(null);
  }

  @Test
  @DisplayName("창마다 첫 경고만 WARN이고 나머지는 DEBUG이며 다음 WARN에 내린 건수를 싣는다")
  void warnsOncePerWindowWithSuppressedCount() {
    warning.at(logger).log("first");
    now.addAndGet(99);
    warning.at(logger).log("second");
    warning.at(logger).log("third");
    now.addAndGet(1);
    warning.at(logger).log("fourth");

    assertThat(logs.list)
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN);
    assertThat(logs.list.getFirst().getKeyValuePairs()).isNull();
    assertThat(logs.list.getLast().getKeyValuePairs())
        .singleElement()
        .satisfies(
            pair -> {
              assertThat(pair.key).isEqualTo("suppressed");
              assertThat(pair.value).isEqualTo(2L);
            });
  }
}
