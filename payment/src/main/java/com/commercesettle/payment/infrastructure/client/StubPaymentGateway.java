package com.commercesettle.payment.infrastructure.client;

import com.commercesettle.payment.application.port.PaymentGateway;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * finance-mock PG 대역(#121)이 병합되기 전 개발용 stub. 모든 승인 요청을 즉시 승인하고, 같은 업무 키 재요청은 같은 결과를 돌려준다. 조회는 이
 * 프로세스가 승인한 거래만 찾고, 재시작 뒤에는 거래 부재로 답해 Worker가 같은 키로 재전송하게 한다. PG 대역이 준비되면 {@code
 * app.payment-approval.gateway=http}로 바꾼다.
 */
public class StubPaymentGateway implements PaymentGateway {
  private final Clock clock;
  private final Map<UUID, Result.Succeeded> approved = new ConcurrentHashMap<>();

  public StubPaymentGateway(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Result approve(ApprovalRequest request) {
    return approved.computeIfAbsent(
        request.businessKey(),
        key ->
            new Result.Succeeded(
                UUID.nameUUIDFromBytes(
                    ("stub-approval:" + request.providerId() + ":" + key)
                        .getBytes(StandardCharsets.UTF_8)),
                clock.instant()));
  }

  @Override
  public Result lookup(ApprovalRequest request) {
    Result.Succeeded found = approved.get(request.businessKey());
    return found == null ? new Result.NotFound() : found;
  }
}
