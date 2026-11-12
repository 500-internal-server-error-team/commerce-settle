package com.commercesettle.payment.infrastructure.client;

import com.commercesettle.internalauth.InternalCredentials;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 승인·환불의 내부 자격과 외부 결과 판정. 조회에서 확인된 거래 부재만 재전송 근거다. */
final class FinanceMockClient {
  private final RestClient client;
  private final String authorization;

  FinanceMockClient(RestClient client, String credential) {
    this.client = client;
    this.authorization =
        "Bearer " + InternalCredentials.requireFormat("service:payment", credential);
  }

  Result exchange(
      Function<RestClient, RestClient.RequestHeadersSpec<?>> request,
      Predicate<MockTransaction> matches,
      boolean lookup) {
    try {
      MockTransaction body =
          request
              .apply(client)
              .header(HttpHeaders.AUTHORIZATION, authorization)
              .retrieve()
              .body(MockTransaction.class);
      if (body == null) return new Result.Unconfirmed("empty response");
      if (!matches.test(body))
        return new Result.Rejected("response does not match the original transaction");
      if (body.id() == null || body.status() == null) {
        return new Result.Unconfirmed("missing transaction result");
      }
      return switch (body.status()) {
        case "SUCCEEDED" ->
            body.succeededAt() == null
                ? new Result.Unconfirmed("succeeded without succeededAt")
                : new Result.Succeeded(body.id(), body.succeededAt().toInstant());
        case "DECLINED" -> new Result.Declined(body.id());
        default -> new Result.Unconfirmed("status " + body.status());
      };
    } catch (HttpClientErrorException e) {
      if (lookup
          && e.getStatusCode().value() == 404
          && "MOCK_TRANSACTION_NOT_FOUND".equals(problemCode(e))) return new Result.NotFound();
      // 409는 처리 중일 수도 있다. 자격 거절과 원승인 부재도 금융 환불의 확정 거절이 아니다.
      if (e.getStatusCode().value() == 400) return new Result.Rejected("HTTP 400");
      return new Result.Unconfirmed("HTTP " + e.getStatusCode().value());
    } catch (RestClientException e) {
      return new Result.Unconfirmed(e.getClass().getSimpleName());
    }
  }

  private static String problemCode(HttpClientErrorException e) {
    try {
      Problem problem = e.getResponseBodyAs(Problem.class);
      return problem == null ? null : problem.code();
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MockTransaction(
      UUID id,
      String providerId,
      String operation,
      UUID businessKey,
      long amount,
      String status,
      String scenarioVersion,
      OffsetDateTime succeededAt,
      String fundingAccountRef,
      UUID originalId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Problem(String code) {}
}
