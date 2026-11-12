package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.port.PaymentGateway.Result;
import java.util.UUID;

/**
 * PG·간편결제 대역의 원거래 전액 환불 계약({@code POST /mock/providers/{providerId}/payments/{externalId}/refunds},
 * {@code GET /mock/providers/{providerId}/transactions/{businessKey}}). 결과 해석은 승인과 같다. 통신
 * 실패·타임아웃·불명확한 응답은 예외 대신 {@link Result.Unconfirmed}이고, 신뢰한 대역의 해당 업무 키 조회에서만 {@link
 * Result.NotFound}다.
 */
public interface RefundGateway {
  Result refund(RefundRequest request);

  Result lookup(RefundRequest request);

  /**
   * 원승인 시도에서 provider·원외부 거래·시나리오 버전을 그대로 가져온다. 금액은 원결제의 외부 결제액(E)뿐이고, 업무 키는 환불의 업무 키다. 재전송도 같은 값을
   * 보낸다.
   */
  record RefundRequest(
      String providerId,
      UUID originalExternalRef,
      UUID businessKey,
      long amount,
      String scenarioVersion) {}
}
