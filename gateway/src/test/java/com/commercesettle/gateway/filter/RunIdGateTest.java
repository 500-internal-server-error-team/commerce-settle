package com.commercesettle.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.gateway.filter.RunIdGate.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RunIdGateTest {
  private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"0123456789abcdef", "0123456789ABCDEF0123456789ABCDEF01234567", RUN_ID + "8"})
  @DisplayName("승인한 run_id가 없거나 소문자 16진수 40자가 아니면 만들지 않는다")
  void rejectsInvalidRunId(String runId) {
    assertThatThrownBy(() -> new RunIdGate(runId)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("확인 전에는 닫혀 있고 연결 지연·단절은 닫는 사유가 아니다")
  void staysCheckingUntilObserved() {
    final var gate = new RunIdGate(RUN_ID);

    gate.disconnected();

    assertThat(gate.state()).isEqualTo(State.CHECKING);
    assertThat(gate.isOpen()).isFalse();
  }

  @Test
  @DisplayName("run_id가 같으면 열고, 연 뒤 단절되면 닫은 채로 다시 열지 않는다")
  void closesForGoodAfterDisconnect() {
    final var gate = new RunIdGate(RUN_ID);

    assertThat(gate.observe(RUN_ID, gate.disconnects())).isEqualTo(State.OPEN);
    gate.disconnected();

    assertThat(gate.state()).isEqualTo(State.CLOSED);
    assertThat(gate.observe(RUN_ID, gate.disconnects())).isEqualTo(State.CLOSED);
  }

  @Test
  @DisplayName("run_id가 다르면 닫고 이후 같은 값을 읽어도 열지 않는다")
  void closesOnMismatch() {
    final var gate = new RunIdGate(RUN_ID);

    assertThat(gate.observe("f".repeat(40), gate.disconnects())).isEqualTo(State.CLOSED);
    assertThat(gate.observe(RUN_ID, gate.disconnects())).isEqualTo(State.CLOSED);
  }

  @Test
  @DisplayName("확인하는 동안 단절됐으면 열지 않고 다음 확인을 기다린다")
  void ignoresCheckInterruptedByDisconnect() {
    final var gate = new RunIdGate(RUN_ID);
    final long before = gate.disconnects();

    gate.disconnected();

    assertThat(gate.observe(RUN_ID, before)).isEqualTo(State.CHECKING);
    assertThat(gate.observe(RUN_ID, gate.disconnects())).isEqualTo(State.OPEN);
  }

  @Test
  @DisplayName("INFO server 응답에서 run_id를 읽는다")
  void readsRunIdFromInfo() {
    final var info =
        "# Server\r\nredis_version:8.0.0\r\nrun_id:" + RUN_ID + "\r\ntcp_port:6379\r\n";

    assertThat(RunIdGate.runId(info)).isEqualTo(RUN_ID);
    assertThat(RunIdGate.runId("# Server\r\n")).isNull();
  }
}
