package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.Notification;
import java.time.Instant;
import java.util.UUID;

/** 호출자는 DB 트랜잭션 밖에서 실행한다. null 조회 결과는 원수신 기록 없음이다. */
public interface NotificationGateway {
  Receipt send(Notification notification, int attempt);

  Receipt lookup(UUID deliveryId, int attempt);

  String requestHash(Notification notification);

  record Receipt(UUID deliveryId, String requestHash, String result, Instant processedAt) {}

  final class Failure extends RuntimeException {
    private final String code;
    private final boolean permanent;
    private final boolean unknown;

    public Failure(String code, boolean permanent, boolean unknown) {
      this.code = code;
      this.permanent = permanent;
      this.unknown = unknown;
    }

    public String code() {
      return code;
    }

    public boolean permanent() {
      return permanent;
    }

    public boolean unknown() {
      return unknown;
    }
  }
}
