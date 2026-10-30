package com.commercesettle.settlement.application.dto;

import java.time.Duration;
import java.util.Objects;

/**
 * 지급 Worker 실행 설정. 잘못된 조합이면 Worker를 열지 않는다.
 *
 * @param leaseDuration 한 번 임대한 작업을 실행할 수 있는 시간
 * @param httpTimeout 대역 호출 한 번의 전체 제한 시간. 임대 시간보다 짧아야 한다
 * @param unconfirmedRetryDelay 결과를 확인하지 못했을 때 원거래를 다시 조회하기까지의 간격
 * @param maxAttempts 회수를 포함한 자동 시도 상한. 넘으면 운영 확인으로 넘긴다
 */
public record PayoutWorkerSettings(
    Duration leaseDuration, Duration httpTimeout, Duration unconfirmedRetryDelay, int maxAttempts) {
  public PayoutWorkerSettings {
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    Objects.requireNonNull(httpTimeout, "httpTimeout");
    Objects.requireNonNull(unconfirmedRetryDelay, "unconfirmedRetryDelay");
    if (httpTimeout.isNegative()
        || httpTimeout.isZero()
        || httpTimeout.compareTo(leaseDuration) >= 0) {
      throw new IllegalArgumentException("0 < httpTimeout < leaseDuration is required");
    }
    if (unconfirmedRetryDelay.isNegative() || maxAttempts <= 0) {
      throw new IllegalArgumentException("retry delay and max attempts must be positive");
    }
  }
}
