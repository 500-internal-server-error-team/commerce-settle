package com.commercesettle.commerce.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 원본 관측의 유효 기간과 중단/재개 경계를 보존하는 인스턴스별 접수 상태. */
public final class OrderAdmissionState {
  private static final Duration FRESHNESS = Duration.ofSeconds(45);
  private static final Comparator<Key> KEY_ORDER =
      Comparator.comparing((Key key) -> key.signal().name()).thenComparing(Key::resource);
  private final Set<Key> required;
  private Map<Key, Sample> samples = Map.of();
  private Instant readySince;
  private boolean open;
  private boolean everOpened;
  private Reason initialReason;
  private Key initialKey;
  private long sequence;

  public OrderAdmissionState(Set<Key> required) {
    if (required.isEmpty()) throw new IllegalArgumentException("Admission signals are required");
    this.required = Set.copyOf(required);
  }

  public synchronized Transition observe(List<Sample> observations, Instant now) {
    // 새 표본이 정상이어도 이전 관측의 공백은 기존 정책대로 연속 회복을 끊는다.
    Transition first = null;
    if (!valid(now)) first = close(invalid(samples.values().stream().toList(), now), now, false);
    Map<Key, Sample> current = new HashMap<>();
    for (Sample sample : observations) {
      if (!required.contains(sample.key()) || !fresh(sample, now)) continue;
      current.merge(
          sample.key(),
          sample,
          (a, b) -> {
            int order = a.observedAt().compareTo(b.observedAt());
            return order == 0 ? (a.value() >= b.value() ? a : b) : (order > 0 ? a : b);
          });
    }
    samples = Map.copyOf(current);
    Transition next = null;
    Sample exceeded = boundary(false);
    if (!valid(now)) {
      next = close(invalid(observations, now), now, true);
    } else if (exceeded != null) {
      next =
          close(
              new Cause(
                  Reason.THRESHOLD_EXCEEDED,
                  exceeded.key(),
                  exceeded,
                  exceeded.key().signal().stop),
              now,
              true);
    } else if (!open) {
      Sample recoveryBlocked = boundary(true);
      if (recoveryBlocked != null) {
        readySince = null;
        next =
            initial(
                new Cause(
                    Reason.RECOVERY_BLOCKED,
                    recoveryBlocked.key(),
                    recoveryBlocked,
                    recoveryBlocked.key().signal().resume),
                now);
      } else {
        if (readySince == null || now.isBefore(readySince)) readySince = now;
        else if (!now.isBefore(readySince.plusSeconds(60))) {
          open = true;
          everOpened = true;
          next = transition(new Cause(Reason.OPEN, null, null, null), now, false);
        }
        if (!open) next = initial(new Cause(Reason.RECOVERING, null, null, 60d), now);
      }
    }
    return first == null ? next : first;
  }

  public synchronized Transition unavailable(Reason reason, Instant now) {
    samples = Map.of();
    return close(new Cause(reason, null, null, null), now, true);
  }

  public synchronized Decision evaluate(Instant now) {
    Transition change = null;
    if (!valid(now)) {
      // 이미 판정한 무효 입력은 상태 조회로 원인이 바뀌지 않는다. 새 입력이나 캐시 만료에서 다시 판정한다.
      boolean diagnoseInitial = initialReason == null || samples.keySet().containsAll(required);
      change = close(invalid(samples.values().stream().toList(), now), now, diagnoseInitial);
    }
    return new Decision(open, change);
  }

  private boolean valid(Instant now) {
    return samples.keySet().containsAll(required)
        && samples.values().stream().allMatch(s -> fresh(s, now));
  }

  private static boolean fresh(Sample s, Instant now) {
    return s.observedAt() != null
        && !s.observedAt().isAfter(now)
        && !s.observedAt().isBefore(now.minus(FRESHNESS))
        && Double.isFinite(s.value())
        && s.value() >= 0;
  }

  private Sample boundary(boolean resume) {
    return samples.values().stream()
        .filter(s -> s.value() >= (resume ? s.key().signal().resume : s.key().signal().stop))
        .min(Comparator.comparing(Sample::key, KEY_ORDER))
        .orElse(null);
  }

  private Cause invalid(List<Sample> observations, Instant now) {
    Key key =
        required.stream()
            .filter(k -> !samples.containsKey(k) || !fresh(samples.get(k), now))
            .min(KEY_ORDER)
            .orElseThrow();
    Sample sample =
        observations.stream()
            .filter(s -> s.key().equals(key))
            .max(
                Comparator.comparing(
                    Sample::observedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    if (sample == null) return new Cause(Reason.MISSING_SAMPLE, key, null, null);
    if (sample.observedAt() == null || !Double.isFinite(sample.value()) || sample.value() < 0)
      return new Cause(Reason.INVALID_SAMPLE, key, sample, null);
    return new Cause(
        sample.observedAt().isAfter(now) ? Reason.FUTURE_SAMPLE : Reason.STALE_SAMPLE,
        key,
        sample,
        (double) FRESHNESS.toSeconds());
  }

  private Transition close(Cause cause, Instant now, boolean reportInitial) {
    boolean wasOpen = open;
    open = false;
    readySince = null;
    if (wasOpen) return transition(cause, now, false);
    return reportInitial ? initial(cause, now) : null;
  }

  private Transition initial(Cause cause, Instant now) {
    if (everOpened
        || (cause.reason() == initialReason && java.util.Objects.equals(cause.key(), initialKey)))
      return null;
    initialReason = cause.reason();
    initialKey = cause.key();
    return transition(cause, now, true);
  }

  private Transition transition(Cause cause, Instant now, boolean initial) {
    Sample sample = cause.sample();
    return new Transition(
        ++sequence,
        open,
        initial,
        cause.reason(),
        now,
        cause.key(),
        sample == null ? null : sample.value(),
        sample == null ? null : sample.observedAt(),
        cause.limit(),
        open ? readySince : null,
        open
            ? samples.values().stream()
                .sorted(Comparator.comparing(Sample::key, KEY_ORDER))
                .toList()
            : List.of());
  }

  public enum Reason {
    OPEN,
    RECOVERING,
    RECOVERY_BLOCKED,
    METRICS_UNAVAILABLE,
    OPERATOR_PAUSED,
    MISSING_SAMPLE,
    INVALID_SAMPLE,
    FUTURE_SAMPLE,
    STALE_SAMPLE,
    THRESHOLD_EXCEEDED
  }

  public record Decision(boolean open, Transition transition) {}

  public record Transition(
      long sequence,
      boolean open,
      boolean initial,
      Reason reason,
      Instant decidedAt,
      Key key,
      Double value,
      Instant observedAt,
      Double limit,
      Instant recoveryStartedAt,
      List<Sample> observations) {}

  private record Cause(Reason reason, Key key, Sample sample, Double limit) {}

  public enum Signal {
    LAG(100_000, 50_000),
    QUEUE_SIZE(100_000, 50_000),
    DUE_AGE(60, 30),
    STORAGE(0.8, 0.7);
    private final double stop;
    private final double resume;

    Signal(double stop, double resume) {
      this.stop = stop;
      this.resume = resume;
    }
  }

  public record Key(Signal signal, String resource) {
    public Key {
      if (signal == null || resource == null || resource.isBlank())
        throw new IllegalArgumentException("Invalid admission signal");
    }
  }

  public record Sample(Key key, double value, Instant observedAt) {}
}
