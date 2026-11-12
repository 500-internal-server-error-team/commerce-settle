package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.domain.model.OrderAdmissionState.Key;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Sample;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Signal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderAdmissionStateTest {
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");

  @ParameterizedTest
  @CsvSource({"LAG,100000,50000", "QUEUE_SIZE,100000,50000", "DUE_AGE,60,30", "STORAGE,0.8,0.7"})
  @DisplayName("각 신호의 중단 경계와 엄격한 재개 경계 및 60초 유지를 적용한다")
  void appliesThresholdsAndHysteresis(Signal signal, double stop, double resume) {
    var key = new Key(signal, "queue");
    var state = new OrderAdmissionState(Set.of(key));
    assertThat(state.permits(NOW)).isFalse();
    observe(state, key, 0, 0);
    observe(state, key, 0, 30);
    assertThat(state.permits(NOW.plusSeconds(59))).isFalse();
    observe(state, key, 0, 60);
    assertThat(state.permits(NOW.plusSeconds(60))).isTrue();
    observe(state, key, resume, 61);
    assertThat(state.permits(NOW.plusSeconds(61))).isTrue();
    observe(state, key, stop, 62);
    assertThat(state.permits(NOW.plusSeconds(62))).isFalse();
    observe(state, key, 0, 63);
    observe(state, key, resume, 90);
    observe(state, key, 0, 91);
    observe(state, key, 0, 121);
    observe(state, key, 0, 150);
    assertThat(state.permits(NOW.plusSeconds(150))).isFalse();
    observe(state, key, 0, 151);
    assertThat(state.permits(NOW.plusSeconds(151))).isTrue();
  }

  @Test
  @DisplayName("요청 중에도 원본 관측이 45초를 초과하면 닫고 회복 시간을 다시 센다")
  void closesExpiredObservationsAndResetsContinuity() {
    var key = new Key(Signal.LAG, "commerce.v1");
    var state = new OrderAdmissionState(Set.of(key));
    observe(state, key, 0, 0);
    observe(state, key, 0, 30);
    observe(state, key, 0, 60);
    assertThat(state.permits(NOW.plusSeconds(105))).isTrue();
    assertThat(state.permits(NOW.plusSeconds(106))).isFalse();
    observe(state, key, 0, 200);
    assertThat(state.permits(NOW.plusSeconds(200))).isFalse();
  }

  @Test
  @DisplayName("요청이 없어도 관측 공백이 45초를 넘으면 연속 재개 조건을 초기화한다")
  void detectsObservationGapWithoutRequests() {
    var key = new Key(Signal.LAG, "payment.v1");
    var state = new OrderAdmissionState(Set.of(key));
    observe(state, key, 0, 0);
    observe(state, key, 0, 100);
    assertThat(state.permits(NOW.plusSeconds(100))).isFalse();
  }

  @Test
  @DisplayName("같은 논리 대기열의 여러 관측을 합산하지 않고 같은 시각이면 큰 값을 쓴다")
  void deduplicatesReplicasWithoutSummingQueues() {
    var key = new Key(Signal.QUEUE_SIZE, "commerce.outbox");
    var state = new OrderAdmissionState(Set.of(key));
    for (int seconds : new int[] {0, 30, 60}) {
      Instant at = NOW.plusSeconds(seconds);
      state.observe(List.of(new Sample(key, 40000, at), new Sample(key, 40000, at)), at);
    }
    assertThat(state.permits(NOW.plusSeconds(60))).isTrue();
    state.observe(
        List.of(
            new Sample(key, 40000, NOW.plusSeconds(61)),
            new Sample(key, 100000, NOW.plusSeconds(61))),
        NOW.plusSeconds(61));
    assertThat(state.permits(NOW.plusSeconds(61))).isFalse();
  }

  @Test
  @DisplayName("필수 대기열 하나의 누락과 미래 시각 및 NaN을 정상으로 대체하지 않는다")
  void rejectsMissingAndInvalidSamples() {
    var a = new Key(Signal.QUEUE_SIZE, "commerce.outbox");
    var b = new Key(Signal.STORAGE, "postgres");
    var state = new OrderAdmissionState(Set.of(a, b));
    for (int seconds : new int[] {0, 30, 60}) {
      Instant at = NOW.plusSeconds(seconds);
      state.observe(List.of(new Sample(a, 0, at), new Sample(b, 0.1, at)), at);
    }
    state.observe(List.of(new Sample(a, 0, NOW.plusSeconds(61))), NOW.plusSeconds(61));
    assertThat(state.permits(NOW.plusSeconds(61))).isFalse();
    state.observe(
        List.of(
            new Sample(a, Double.NaN, NOW.plusSeconds(62)), new Sample(b, 0, NOW.plusSeconds(63))),
        NOW.plusSeconds(62));
    assertThat(state.permits(NOW.plusSeconds(62))).isFalse();
  }

  private static void observe(OrderAdmissionState state, Key key, double value, int seconds) {
    Instant at = NOW.plusSeconds(seconds);
    state.observe(List.of(new Sample(key, value, at)), at);
  }
}
