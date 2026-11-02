package com.commercesettle.settlement.application.exception;

/** 운영 요청을 현재 상태에서 받아들일 수 없다. 사유마다 응답 코드가 다르다. 아무것도 바꾸지 않는다. */
public class OperationRejectedException extends RuntimeException {
  private final Reason reason;

  public OperationRejectedException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    /** 예상 버전이 현재 버전과 다르다. */
    VERSION_CONFLICT,
    /** 지급 작업이 실행 중(임대 보유)이다. */
    LEASE_ACTIVE,
    /** 운영 확인 대기가 아니거나, 같은 업무 키로 다시 돌려도 결과가 바뀌지 않는 지급(확정 거절·성공)이다. */
    NOT_REPLAYABLE,
    /** 배치 마감이 아직 지나지 않았다. */
    BATCH_NOT_DUE,
    /** 지원하지 않는 정산 정책 버전이다. */
    POLICY_UNSUPPORTED,
    /** 기존 배치도 없고 마감 전 지급 대상도 없다. */
    NO_ELIGIBLE_ITEMS
  }
}
