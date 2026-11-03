package com.commercesettle.payment.infrastructure.client;

import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.infrastructure.client.FinanceMockClient.MockTransaction;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Finance Mock 승인·원거래 조회. 고정한 승인 요청과 일치하는 거래만 확정 결과로 받는다. */
public class HttpPaymentGateway implements PaymentGateway {
  private final FinanceMockClient client;

  public HttpPaymentGateway(RestClient client, String credential) {
    this.client = new FinanceMockClient(client, credential);
  }

  @Override
  public Result approve(ApprovalRequest request) {
    return client.exchange(
        c ->
            c.post()
                .uri("/mock/providers/{providerId}/payments", request.providerId())
                .header("Idempotency-Key", "approve-" + request.businessKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    new MockApproval(
                        request.businessKey(),
                        request.method(),
                        request.accountRef(),
                        request.amount(),
                        request.scenarioVersion(),
                        request.fundingAccountRef())),
        body -> matches(body, request),
        false);
  }

  @Override
  public Result lookup(ApprovalRequest request) {
    return client.exchange(
        c ->
            c.get()
                .uri(
                    "/mock/providers/{providerId}/transactions/{businessKey}"
                        + "?operation=APPROVE&scenarioVersion={scenarioVersion}",
                    request.providerId(),
                    request.businessKey(),
                    request.scenarioVersion()),
        body -> matches(body, request),
        true);
  }

  private static boolean matches(MockTransaction body, ApprovalRequest request) {
    return request.providerId().equals(body.providerId())
        && request.businessKey().equals(body.businessKey())
        && body.amount() == request.amount()
        && "APPROVE".equals(body.operation())
        && request.scenarioVersion().equals(body.scenarioVersion())
        && request.fundingAccountRef().equals(body.fundingAccountRef());
  }

  record MockApproval(
      UUID businessKey,
      String method,
      String accountRef,
      long amount,
      String scenarioVersion,
      String fundingAccountRef) {}
}
