package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * PG·간편결제 대역의 승인 호출 계약(`POST /mock/providers/{providerId}/payments`, `GET
 * /mock/providers/{providerId}/transactions/{businessKey}`). 통신 실패·타임아웃·불명확한 응답은 예외 대신 {@link
 * Result.Unconfirmed}로 돌려준다.
 */
public interface PaymentGateway {
  Result approve(ApprovalRequest request);

  Result lookup(ApprovalRequest request);

  /** 승인 시도를 만들 때 고정한 값으로만 만든다. 재전송도 같은 값을 보낸다. */
  record ApprovalRequest(
      String providerId,
      UUID businessKey,
      String method,
      String accountRef,
      String fundingAccountRef,
      long amount,
      String scenarioVersion) {}

  sealed interface Result {
    /** 대역이 확인한 승인. */
    record Succeeded(UUID externalRef, Instant succeededAt) implements Result {}

    /** 대역의 확정 거절. */
    record Declined(UUID externalRef) implements Result {}

    /** 처리 중·응답 유실·조회 장애·통신 실패. 실패로 해석하지 않고 같은 원거래를 다시 조회한다. */
    record Unconfirmed(String reason) implements Result {}

    /** 신뢰한 대역의 해당 업무 키 조회에서만 받는 거래 부재. 원키·원본문으로 재전송한다. */
    record NotFound() implements Result {}

    /** 형식 오류·원거래 불일치처럼 재시도로 해결되지 않는 응답. 운영 확인으로 넘긴다. */
    record Rejected(String reason) implements Result {}
  }
}
