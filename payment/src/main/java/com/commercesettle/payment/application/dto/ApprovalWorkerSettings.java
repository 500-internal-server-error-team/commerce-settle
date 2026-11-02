package com.commercesettle.payment.application.dto;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 승인 Worker 설정. 호출 제한 시간은 임대보다 짧아야 한다. 승인 시도의 provider·재원·시나리오는 시도를 만들 때 이 값으로 고정한다.
 *
 * @param cardProvider 카드 결제를 보낼 PG(PG_A·PG_B·PG_C). 간편결제는 EASY_PAY로 보낸다.
 */
public record ApprovalWorkerSettings(
    Duration leaseDuration,
    Duration httpTimeout,
    Duration unconfirmedRetryDelay,
    int maxAttempts,
    String cardProvider,
    String fundingAccountRef,
    String scenarioVersion) {
  public ApprovalWorkerSettings {
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    Objects.requireNonNull(httpTimeout, "httpTimeout");
    Objects.requireNonNull(unconfirmedRetryDelay, "unconfirmedRetryDelay");
    if (httpTimeout.isZero()
        || httpTimeout.isNegative()
        || httpTimeout.compareTo(leaseDuration) >= 0
        || unconfirmedRetryDelay.isNegative()
        || maxAttempts < 1
        || !Set.of("PG_A", "PG_B", "PG_C").contains(cardProvider)
        || fundingAccountRef == null
        || fundingAccountRef.isBlank()
        || scenarioVersion == null
        || scenarioVersion.isBlank()) {
      throw new IllegalArgumentException("Invalid approval worker settings");
    }
  }
}
