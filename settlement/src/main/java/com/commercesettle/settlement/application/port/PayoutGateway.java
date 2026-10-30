package com.commercesettle.settlement.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * 지급 기관 대역(`POST /mock/payouts`, `GET /mock/payouts/{businessKey}`) 호출 계약. 통신 실패·타임아웃·불명확한 응답은 예외
 * 대신 {@link Result.Unconfirmed}로 돌려준다.
 */
public interface PayoutGateway {
  Result execute(PayoutRequest request);

  Result lookup(PayoutRequest request);

  /** 지급 생성 때 고정한 값으로만 만든다. 재요청도 같은 값을 보낸다. */
  record PayoutRequest(
      UUID businessKey,
      String fundingAccountRef,
      String recipientAccountRef,
      long amount,
      String scenarioVersion) {}

  sealed interface Result {
    /** 대역이 확인한 성공. succeededAt은 대역의 최초 성공 시각이다. */
    record Succeeded(UUID externalRef, Instant succeededAt) implements Result {}

    /** 대역의 확정 거절. */
    record Declined(UUID externalRef) implements Result {}

    /** 처리 중·응답 유실·조회 장애·통신 실패. 실패로 해석하지 않고 같은 원거래를 다시 조회한다. */
    record Unconfirmed(String reason) implements Result {}

    /** 신뢰한 대역의 해당 업무 키 조회에서만 받는 거래 부재. 원키·원본문으로 재전송한다. */
    record NotFound() implements Result {}

    /** 형식 오류·본문 충돌·원거래 불일치처럼 재시도로 해결되지 않는 응답. 운영 확인으로 넘긴다. */
    record Rejected(String reason) implements Result {}
  }
}
