package com.commercesettle.commerce.application.port;

import java.util.UUID;

/** 원결과 조회만 수행한다. 통신/조회 실패·미확정은 종결 근거가 아니다. */
public interface RefundClosureEvidence {
  Evidence verify(UUID refundId, String customerConfirmationRef);

  record Evidence(
      UUID refundId,
      boolean externalDeclined,
      boolean mileageNotRestored,
      boolean reservationReleased,
      boolean customerConfirmed,
      String reference) {
    public boolean safe() {
      return refundId != null
          && externalDeclined
          && mileageNotRestored
          && reservationReleased
          && customerConfirmed
          && reference != null
          && !reference.isBlank();
    }
  }
}
