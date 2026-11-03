package com.commercesettle.payment.infrastructure.client;

import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>외부 환불 stub.</b> finance-mock 환불 대역 연동 전 개발용. 모든 환불을 즉시 성공시키고, 같은 업무 키 재요청은 같은 결과를 돌려준다. 조회는 이
 * 프로세스가 처리한 환불만 찾고, 재시작 뒤에는 거래 부재로 답해 Worker가 같은 키로 재전송하게 한다. 실제 대역의 금전 효과는 없다.
 *
 * <p>교체 지점: finance-mock 환불 경로를 호출하는 HTTP 구현을 추가하고 {@code app.refund.gateway}로 고른다.
 */
public class StubRefundGateway implements RefundGateway {
  private final Clock clock;
  private final Map<UUID, Result.Succeeded> refunded = new ConcurrentHashMap<>();

  public StubRefundGateway(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Result refund(RefundRequest request) {
    return refunded.computeIfAbsent(
        request.businessKey(),
        key ->
            new Result.Succeeded(
                UUID.nameUUIDFromBytes(
                    ("stub-refund:" + request.providerId() + ":" + key)
                        .getBytes(StandardCharsets.UTF_8)),
                clock.instant()));
  }

  @Override
  public Result lookup(RefundRequest request) {
    Result.Succeeded found = refunded.get(request.businessKey());
    return found == null ? new Result.NotFound() : found;
  }
}
