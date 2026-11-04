package com.commercesettle.settlement.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercesettle.settlement.application.port.InboxOperationStore.Claim;
import com.commercesettle.settlement.application.service.InboxOperationService;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;

class InboxRejudgementSchedulerTest {
  private static final String SECRET = "DO_NOT_LOG_PAYLOAD";
  private final InboxOperationService service = mock(InboxOperationService.class);
  private final InboxRejudgementScheduler scheduler =
      new InboxRejudgementScheduler(service, Duration.ofSeconds(30));
  private final Logger logger = (Logger) LoggerFactory.getLogger(InboxRejudgementScheduler.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void capture() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void release() {
    logger.detachAppender(logs);
    logs.stop();
  }

  @ParameterizedTest
  @MethodSource("claimFailures")
  @DisplayName("임대 실패는 일시적 저장소 장애와 예상 밖 결함을 구분하고 예외 원문을 숨긴다")
  void classifiesClaimFailureWithoutLeakingInput(RuntimeException failure, Level level) {
    when(service.claim(any())).thenThrow(failure);

    scheduler.poll();

    assertThat(logs.list).hasSize(1);
    assertFailure(logs.list.getFirst(), level, "CLAIM", failure.getClass(), null);
  }

  static Stream<Arguments> claimFailures() {
    return Stream.of(
        Arguments.of(new QueryTimeoutException(SECRET), Level.WARN),
        Arguments.of(new DataAccessResourceFailureException(SECRET), Level.WARN),
        Arguments.of(new CannotCreateTransactionException(SECRET), Level.WARN),
        Arguments.of(new IllegalStateException(SECRET), Level.ERROR),
        Arguments.of(
            new BadSqlGrammarException(SECRET, "SELECT " + SECRET, new SQLException(SECRET)),
            Level.ERROR));
  }

  @Test
  @DisplayName("적용 실패는 대상·실행과 연결하고 민감한 예외 메시지 없이 ERROR로 기록한다")
  void linksUnexpectedApplicationFailureToClaim() {
    Claim claim = claim();
    when(service.claim(any())).thenReturn(Optional.of(claim), Optional.empty());
    when(service.process(claim)).thenThrow(new IllegalStateException(SECRET));

    scheduler.poll();

    assertThat(logs.list).hasSize(1);
    assertFailure(logs.list.getFirst(), Level.ERROR, "APPLY", IllegalStateException.class, claim);
  }

  @Test
  @DisplayName("실패 기록도 실패하면 같은 대상을 연결하되 적용 실패와 단계를 구분한다")
  void distinguishesFailureRecordingFromApplication() {
    Claim claim = claim();
    when(service.claim(any())).thenReturn(Optional.of(claim));
    when(service.process(claim)).thenThrow(new IllegalStateException(SECRET));
    doThrow(new CannotCreateTransactionException(SECRET)).when(service).fail(claim);

    scheduler.poll();

    assertThat(logs.list).hasSize(2);
    assertFailure(logs.list.get(0), Level.ERROR, "APPLY", IllegalStateException.class, claim);
    assertFailure(
        logs.list.get(1),
        Level.WARN,
        "FAILURE_RECORD",
        CannotCreateTransactionException.class,
        claim);
  }

  private void assertFailure(
      ILoggingEvent event, Level level, String stage, Class<?> errorType, Claim claim) {
    assertThat(event.getLevel()).isEqualTo(level);
    Map<String, Object> fields = new LinkedHashMap<>();
    event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
    assertThat(fields)
        .containsEntry("event", "settlement.inbox-rejudgement")
        .containsEntry("errorCode", "PROCESSING_FAILED")
        .containsEntry("stage", stage)
        .containsEntry("errorType", errorType.getName());
    if (claim == null) assertThat(fields).doesNotContainKeys("runId", "targetId", "inboxId");
    else
      assertThat(fields)
          .containsEntry("runId", claim.runId())
          .containsEntry("targetId", claim.targetId())
          .containsEntry("inboxId", claim.inboxId());
    assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
    assertThat(fields.toString()).doesNotContain(SECRET);
    assertThat(event.getThrowableProxy()).isNull();
  }

  private Claim claim() {
    return new Claim(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        "PAYMENT_NOT_ARRIVED",
        0,
        "PENDING");
  }
}
