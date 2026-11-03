package com.commercesettle.payment.infrastructure.client;

import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.infrastructure.client.FinanceMockClient.MockTransaction;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** 원승인에 외부 결제액만 환불한다. 유실·처리 중·조회 장애는 같은 환불 키의 조회로 확인한다. */
public class HttpRefundGateway implements RefundGateway {
  private final FinanceMockClient client;

  public HttpRefundGateway(RestClient client, String credential) {
    this.client = new FinanceMockClient(client, credential);
  }

  @Override
  public Result refund(RefundRequest request) {
    return client.exchange(
        c ->
            c.post()
                .uri(
                    "/mock/providers/{providerId}/payments/{externalId}/refunds",
                    request.providerId(),
                    request.originalExternalRef())
                .header("Idempotency-Key", "refund-" + request.businessKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MockRefund(request.businessKey(), request.amount())),
        body -> matches(body, request),
        false);
  }

  @Override
  public Result lookup(RefundRequest request) {
    return client.exchange(
        c ->
            c.get()
                .uri(
                    "/mock/providers/{providerId}/transactions/{businessKey}"
                        + "?operation=REFUND&scenarioVersion={scenarioVersion}",
                    request.providerId(),
                    request.businessKey(),
                    request.scenarioVersion()),
        body -> matches(body, request),
        true);
  }

  private static boolean matches(MockTransaction body, RefundRequest request) {
    return request.providerId().equals(body.providerId())
        && request.businessKey().equals(body.businessKey())
        && request.originalExternalRef().equals(body.originalId())
        && body.amount() == request.amount()
        && "REFUND".equals(body.operation())
        && request.scenarioVersion().equals(body.scenarioVersion());
  }

  record MockRefund(UUID businessKey, long amount) {}
}
