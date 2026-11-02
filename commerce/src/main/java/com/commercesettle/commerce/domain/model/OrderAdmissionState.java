package com.commercesettle.commerce.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 원본 관측의 유효 기간과 중단/재개 경계를 보존하는 인스턴스별 접수 상태. */
public final class OrderAdmissionState {
  private static final Duration FRESHNESS = Duration.ofSeconds(45);
  private final Set<Key> required;
  private Map<Key, Sample> samples = Map.of();
  private Instant readySince;
  private boolean open;

  public OrderAdmissionState(Set<Key> required) {
    if (required.isEmpty()) throw new IllegalArgumentException("Admission signals are required");
    this.required = Set.copyOf(required);
  }

  public synchronized void observe(List<Sample> observations, Instant now) {
    if (!valid(now)) close();
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
    if (!valid(now)
        || current.values().stream().anyMatch(s -> s.value() >= s.key().signal().stop)) {
      close();
    } else if (!open) {
      if (current.values().stream().anyMatch(s -> s.value() >= s.key().signal().resume)) {
        readySince = null;
      } else if (readySince == null || now.isBefore(readySince)) {
        readySince = now;
      } else if (!now.isBefore(readySince.plusSeconds(60))) {
        open = true;
      }
    }
  }

  public synchronized void unavailable() {
    samples = Map.of();
    close();
  }

  public synchronized boolean permits(Instant now) {
    if (!valid(now)) close();
    return open;
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

  private void close() {
    open = false;
    readySince = null;
  }

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
