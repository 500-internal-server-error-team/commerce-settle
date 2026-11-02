package com.commercesettle.commerce.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 주문 주소·알림만 다루며 호출자가 로컬 트랜잭션을 소유한다. */
public interface RetentionStore {
  Duration PERIOD = Duration.ofDays(90);

  enum Target {
    ORDER,
    DELIVERY
  }

  List<UUID> due(Target target, Instant now, int limit, Set<UUID> skipped);

  String process(Target target, UUID id, Instant now);

  void failed(Target target, UUID id, Instant now);

  void hold(UUID orderId, String reason, Instant now);
}
