package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.service.SaleEventService.GaugeView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공개 게이지 조회의 인스턴스 메모리 캐시. 이벤트별로 TTL 안의 재조회는 처음 계산한 값(asOf 포함)을 돌려주고, 동시 미스는 한 번만 DB를 읽는다. 외부 캐시가
 * 아니므로 별도 장애 경로가 없고, 조회 실패(없는 이벤트·DB 오류)는 저장하지 않아 다음 요청이 다시 읽는다. 구매 허용 판단은 이 캐시를 쓰지 않고 DB 조건부 갱신이
 * 한다. TTL이 0이면 캐시하지 않는다.
 */
@Component
public class EventGaugeCache {
  /** 이 수를 넘으면 만료된 항목을 정리한다. 공개 이벤트 수만큼만 쌓인다. */
  static final int CLEANUP_THRESHOLD = 1_000;

  private final SaleEventService events;
  private final Clock clock;
  private final Duration ttl;
  private final ConcurrentHashMap<UUID, GaugeView> entries = new ConcurrentHashMap<>();

  public EventGaugeCache(
      SaleEventService events,
      Clock clock,
      @Value("${app.sale-events.gauge-cache-ttl:PT1S}") Duration ttl) {
    if (ttl.isNegative()) {
      throw new IllegalArgumentException("gauge cache ttl must not be negative");
    }
    this.events = events;
    this.clock = clock;
    this.ttl = ttl;
  }

  public GaugeView gauge(UUID eventId) {
    if (ttl.isZero()) {
      return events.gauge(eventId);
    }
    Instant now = clock.instant();
    GaugeView cached = entries.get(eventId);
    if (fresh(cached, now)) {
      return cached;
    }
    if (entries.size() > CLEANUP_THRESHOLD) {
      entries.values().removeIf(view -> !fresh(view, now));
    }
    return entries.compute(
        eventId, (id, current) -> fresh(current, clock.instant()) ? current : events.gauge(id));
  }

  private boolean fresh(GaugeView view, Instant now) {
    return view != null && now.isBefore(view.asOf().plus(ttl));
  }
}
