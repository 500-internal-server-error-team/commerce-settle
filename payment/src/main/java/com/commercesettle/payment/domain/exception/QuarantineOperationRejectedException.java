package com.commercesettle.payment.domain.exception;

import java.util.Objects;

/** 운영자의 격리 원문 재처리·발행 재개 요청을 현재 상태에서 받을 수 없다. 응답 code는 {@link Reason#code()}다. */
public class QuarantineOperationRejectedException extends RuntimeException {
  private final Reason reason;

  public QuarantineOperationRejectedException(Reason reason) {
    super("Quarantine operation rejected: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    /** 요청의 expectedVersion이 원문 또는 작업의 현재 version과 다르다. */
    VERSION_CONFLICT("COMMON_VERSION_CONFLICT"),
    /** 같은 격리 원문의 REPLAY가 아직 발행 중(PENDING/RUNNING/RETRY)이다. */
    REPLAY_ACTIVE("QUARANTINE_REPLAY_ACTIVE"),
    /** 발행 작업이 시도를 다 쓴 운영 확인(REVIEW_REQUIRED) 상태가 아니다. */
    DELIVERY_NOT_RESUMABLE("QUARANTINE_DELIVERY_NOT_RESUMABLE");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
