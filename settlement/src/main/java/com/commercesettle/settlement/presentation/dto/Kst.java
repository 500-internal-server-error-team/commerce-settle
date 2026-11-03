package com.commercesettle.settlement.presentation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 응답 시각은 KST(+09:00)로 내보낸다(CONTRIBUTING 요청과 응답). */
final class Kst {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private Kst() {}

  static OffsetDateTime of(Instant value) {
    return value == null ? null : value.atZone(SEOUL).toOffsetDateTime();
  }
}
