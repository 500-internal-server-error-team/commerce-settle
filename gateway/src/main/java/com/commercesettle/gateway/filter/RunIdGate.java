package com.commercesettle.gateway.filter;

import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 승인한 Redis 실행 식별자(run_id)로 사용자 인증을 열고 닫는 게이트. 커머스에 같은 규칙의 게이트가 있다.
 *
 * <p>확인 전(CHECKING)에서 시작해 연결한 Redis의 run_id가 승인값과 같을 때만 연다. 열린 뒤 연결이 끊기거나 run_id가 다르면 닫고, 재기동 전까지
 * 다시 열지 않는다. 기준은 docs/policies.md#폐기와-저장소-복구와 docs/development.md#redis-세션-검증과-복구다.
 */
public final class RunIdGate {
  private static final Logger LOGGER = LoggerFactory.getLogger(RunIdGate.class);
  private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{40}");
  private static final String RUN_ID_FIELD = "run_id:";

  public enum State {
    CHECKING,
    OPEN,
    CLOSED
  }

  private final String approvedRunId;
  private volatile State state = State.CHECKING;
  private long disconnects;

  /** 승인값이 Redis run_id 형식(소문자 16진수 40자)이 아니면 기동하지 않도록 예외를 던진다. */
  public RunIdGate(String approvedRunId) {
    if (approvedRunId == null || !RUN_ID.matcher(approvedRunId).matches()) {
      throw new IllegalArgumentException(
          "Redis run_id must be 40 lowercase hexadecimal characters");
    }
    this.approvedRunId = approvedRunId;
  }

  public State state() {
    return state;
  }

  public boolean isOpen() {
    return state == State.OPEN;
  }

  /** 확인을 시작하기 전에 읽어 {@link #observe}에 넘긴다. 그 사이의 단절을 가려낸다. */
  public synchronized long disconnects() {
    return disconnects;
  }

  /**
   * {@code INFO server}로 읽은 run_id를 판정한다. 확인 전 상태에서만 바꾸며, 다르면 닫고 같으면 연다. 확인하는 동안 연결이 끊겼으면 다른 Redis에
   * 다시 붙었을 수 있으므로 열지 않고 다음 확인을 기다린다.
   */
  public synchronized State observe(String runId, long disconnectsBeforeCheck) {
    if (state != State.CHECKING) {
      return state;
    }
    if (!approvedRunId.equals(runId)) {
      close("RUN_ID_MISMATCH", runId);
    } else if (disconnects == disconnectsBeforeCheck) {
      state = State.OPEN;
      LOGGER
          .atDebug()
          .addKeyValue("event", "auth.redis-gate")
          .addKeyValue("state", State.OPEN)
          .addKeyValue("runId", runId)
          .log("Redis auth gate opened");
    }
    return state;
  }

  /** 사용자가 닫지 않은 연결이 끊겼을 때 부른다. 열린 게이트는 닫고, 확인 전이면 진행 중인 확인을 무효로 만든다. */
  public synchronized void disconnected() {
    disconnects++;
    if (state == State.OPEN) {
      close("DISCONNECTED", null);
    }
  }

  /** {@code INFO server} 응답에서 run_id를 읽는다. 없으면 null이다. */
  public static String runId(String info) {
    return info.lines()
        .filter(line -> line.startsWith(RUN_ID_FIELD))
        .map(line -> line.substring(RUN_ID_FIELD.length()).strip())
        .findFirst()
        .orElse(null);
  }

  private void close(String reason, String observedRunId) {
    state = State.CLOSED;
    LOGGER
        .atError()
        .addKeyValue("event", "auth.redis-gate")
        .addKeyValue("state", State.CLOSED)
        .addKeyValue("reason", reason)
        .addKeyValue("closedAt", Instant.now())
        .addKeyValue("approvedRunId", approvedRunId)
        .addKeyValue("observedRunId", observedRunId)
        .log("Redis auth gate closed; restart with a new session epoch and run_id to recover");
  }
}
