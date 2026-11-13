package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.domain.model.OrderAdmissionState.Key;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Reason;
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
    assertThat(state.evaluate(NOW).open()).isFalse();
    observe(state, key, 0, 0);
    observe(state, key, 0, 30);
    assertThat(state.evaluate(NOW.plusSeconds(59)).open()).isFalse();
    observe(state, key, 0, 60);
    assertThat(state.evaluate(NOW.plusSeconds(60)).open()).isTrue();
    observe(state, key, resume, 61);
    assertThat(state.evaluate(NOW.plusSeconds(61)).open()).isTrue();
    var change =
        state.observe(List.of(new Sample(key, stop, NOW.plusSeconds(62))), NOW.plusSeconds(62));
    assertThat(change.reason()).isEqualTo(Reason.THRESHOLD_EXCEEDED);
    assertThat(change.key()).isEqualTo(key);
    assertThat(change.value()).isEqualTo(stop);
    assertThat(change.limit()).isEqualTo(stop);
    assertThat(change.observedAt()).isEqualTo(NOW.plusSeconds(62));
    assertThat(state.evaluate(NOW.plusSeconds(62)).open()).isFalse();
    observe(state, key, 0, 63);
    observe(state, key, resume, 90);
    observe(state, key, 0, 91);
    observe(state, key, 0, 121);
    observe(state, key, 0, 150);
    assertThat(state.evaluate(NOW.plusSeconds(150)).open()).isFalse();
    observe(state, key, 0, 151);
    assertThat(state.evaluate(NOW.plusSeconds(151)).open()).isTrue();
  }

  @Test
  @DisplayName("요청 중에도 원본 관측이 45초를 초과하면 닫고 회복 시간을 다시 센다")
  void closesExpiredObservationsAndResetsContinuity() {
    var key = new Key(Signal.LAG, "commerce.v1");
    var state = new OrderAdmissionState(Set.of(key));
    observe(state, key, 0, 0);
    observe(state, key, 0, 30);
    observe(state, key, 0, 60);
    assertThat(state.evaluate(NOW.plusSeconds(105)).open()).isTrue();
    assertThat(state.evaluate(NOW.plusSeconds(106)).open()).isFalse();
    observe(state, key, 0, 200);
    assertThat(state.evaluate(NOW.plusSeconds(200)).open()).isFalse();
  }

  @Test
  @DisplayName("요청이 없어도 관측 공백이 45초를 넘으면 연속 재개 조건을 초기화한다")
  void detectsObservationGapWithoutRequests() {
    var key = new Key(Signal.LAG, "payment.v1");
    var state = new OrderAdmissionState(Set.of(key));
    observe(state, key, 0, 0);
    observe(state, key, 0, 100);
    assertThat(state.evaluate(NOW.plusSeconds(100)).open()).isFalse();
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
    assertThat(state.evaluate(NOW.plusSeconds(60)).open()).isTrue();
    state.observe(
        List.of(
            new Sample(key, 40000, NOW.plusSeconds(61)),
            new Sample(key, 100000, NOW.plusSeconds(61))),
        NOW.plusSeconds(61));
    assertThat(state.evaluate(NOW.plusSeconds(61)).open()).isFalse();
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
    assertThat(state.evaluate(NOW.plusSeconds(61)).open()).isFalse();
    state.observe(
        List.of(
            new Sample(a, Double.NaN, NOW.plusSeconds(62)), new Sample(b, 0, NOW.plusSeconds(63))),
        NOW.plusSeconds(62));
    assertThat(state.evaluate(NOW.plusSeconds(62)).open()).isFalse();
  }

  @Test
  @DisplayName("새 입력이 임계값을 넘겨도 이전 표본 만료가 먼저 닫았다면 그 근거를 반환한다")
  void preservesFirstClosureWhenFreshInputAlsoExceedsThreshold() {
    var key = new Key(Signal.LAG, "commerce.v1");
    var state = new OrderAdmissionState(Set.of(key));
    for (int seconds : new int[] {0, 30, 60}) observe(state, key, 0, seconds);

    var change =
        state.observe(List.of(new Sample(key, 100000, NOW.plusSeconds(106))), NOW.plusSeconds(106));

    assertThat(change.reason()).isEqualTo(Reason.STALE_SAMPLE);
    assertThat(change.value()).isZero();
    assertThat(change.observedAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(state.evaluate(NOW.plusSeconds(106)).transition()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING_SAMPLE,missing",
    "INVALID_SAMPLE,nan",
    "INVALID_SAMPLE,negative",
    "INVALID_SAMPLE,noTime",
    "FUTURE_SAMPLE,future",
    "STALE_SAMPLE,stale"
  })
  @DisplayName("초기 준비를 막은 누락·잘못된 값·시각 문제를 원래 입력으로 구분한다")
  void describesInvalidInitialInput(Reason reason, String scenario) {
    var key = new Key(Signal.STORAGE, "postgres");
    var state = new OrderAdmissionState(Set.of(key));
    List<Sample> input =
        switch (scenario) {
          case "missing" -> List.of();
          case "nan" -> List.of(new Sample(key, Double.NaN, NOW));
          case "negative" -> List.of(new Sample(key, -1, NOW));
          case "noTime" -> List.of(new Sample(key, 0, null));
          case "future" -> List.of(new Sample(key, 0, NOW.plusSeconds(1)));
          default -> List.of(new Sample(key, 0, NOW.minusSeconds(46)));
        };

    var change = state.observe(input, NOW);

    assertThat(change.initial()).isTrue();
    assertThat(change.reason()).isEqualTo(reason);
    assertThat(change.key()).isEqualTo(key);
    assertThat(state.evaluate(NOW).open()).isFalse();
  }

  @Test
  @DisplayName("동시에 표본 만료를 확인해도 최초 차단 전환은 한 번만 반환한다")
  void returnsOneClosureToConcurrentCallers() throws Exception {
    var key = new Key(Signal.LAG, "commerce.v1");
    var state = new OrderAdmissionState(Set.of(key));
    for (int seconds : new int[] {0, 30, 60}) observe(state, key, 0, seconds);
    var futures =
        new java.util.ArrayList<java.util.concurrent.Future<OrderAdmissionState.Decision>>();

    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 100; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return state.evaluate(NOW.plusSeconds(106));
                }));
      start.countDown();
      int transitions = 0;
      for (var future : futures) {
        var decision = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(decision.open()).isFalse();
        if (decision.transition() != null) {
          transitions++;
          assertThat(decision.transition().reason()).isEqualTo(Reason.STALE_SAMPLE);
        }
      }
      assertThat(transitions).isEqualTo(1);
    }
  }

  private static void observe(OrderAdmissionState state, Key key, double value, int seconds) {
    Instant at = NOW.plusSeconds(seconds);
    state.observe(List.of(new Sample(key, value, at)), at);
  }
}
