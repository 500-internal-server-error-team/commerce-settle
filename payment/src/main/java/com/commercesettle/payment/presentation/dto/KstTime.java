package com.commercesettle.payment.presentation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 응답 시각은 KST 오프셋으로 내보낸다. */
final class KstTime {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private KstTime() {}

  static OffsetDateTime of(Instant value) {
    return value == null ? null : value.atZone(SEOUL).toOffsetDateTime();
  }
}
