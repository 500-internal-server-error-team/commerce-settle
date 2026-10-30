package com.commercesettle.settlement.infrastructure.client;

import com.commercesettle.settlement.application.port.PayoutGateway;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * finance-mock 지급 대역의 HTTP 구현. 성공·확정 거절만 확정 결과로 보고, 처리 중·5xx·504(응답 유실)·타임아웃은 미확정으로 돌려준다. 조회의 404는
 * 대역이 MOCK_TRANSACTION_NOT_FOUND라고 답했을 때만 거래 부재로 본다. 응답의 업무 키·금액·작업·출금 계정·금융 시나리오 버전이 요청과 다르면 결과를
 * 믿지 않는다.
 */
public class HttpPayoutGateway implements PayoutGateway {
  private static final String NOT_FOUND_CODE = "MOCK_TRANSACTION_NOT_FOUND";

  private final RestClient client;
  private final String lookupScenarioVersion;

  public HttpPayoutGateway(RestClient client, String lookupScenarioVersion) {
    this.client = client;
    this.lookupScenarioVersion = lookupScenarioVersion;
  }

  @Override
  public Result execute(PayoutRequest request) {
    try {
      MockTransaction body =
          client
              .post()
              .uri("/mock/payouts")
              .header("Idempotency-Key", "payout-" + request.businessKey())
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new MockPayout(
                      request.businessKey(),
                      request.fundingAccountRef(),
                      request.recipientAccountRef(),
                      request.amount(),
                      request.scenarioVersion()))
              .retrieve()
              .body(MockTransaction.class);
      return interpret(body, request);
    } catch (HttpClientErrorException e) {
      return rejectedOrUnconfirmed(e);
    } catch (RestClientException e) {
      return new Result.Unconfirmed(e.getClass().getSimpleName());
    }
  }

  @Override
  public Result lookup(PayoutRequest request) {
    try {
      MockTransaction body =
          client
              .get()
              .uri(
                  "/mock/payouts/{businessKey}?scenarioVersion={scenarioVersion}",
                  request.businessKey(),
                  lookupScenarioVersion)
              .retrieve()
              .body(MockTransaction.class);
      return interpret(body, request);
    } catch (HttpClientErrorException.NotFound e) {
      return NOT_FOUND_CODE.equals(problemCode(e))
          ? new Result.NotFound()
          : new Result.Unconfirmed("HTTP 404 without transaction-not-found code");
    } catch (HttpClientErrorException e) {
      return rejectedOrUnconfirmed(e);
    } catch (RestClientException e) {
      return new Result.Unconfirmed(e.getClass().getSimpleName());
    }
  }

  private static Result interpret(MockTransaction body, PayoutRequest request) {
    if (body == null) {
      return new Result.Unconfirmed("empty response");
    }
    if (!request.businessKey().equals(body.businessKey())
        || body.amount() != request.amount()
        || !"PAYOUT".equals(body.operation())
        || !request.fundingAccountRef().equals(body.fundingAccountRef())
        || !request.scenarioVersion().equals(body.scenarioVersion())) {
      return new Result.Rejected("response does not match the original payout");
    }
    return switch (body.status()) {
      case "SUCCEEDED" ->
          body.succeededAt() == null
              ? new Result.Unconfirmed("succeeded without succeededAt")
              : new Result.Succeeded(body.id(), body.succeededAt().toInstant());
      case "DECLINED" -> new Result.Declined(body.id());
      default -> new Result.Unconfirmed("status " + body.status());
    };
  }

  /**
   * 형식 오류는 같은 요청을 다시 보내도 해결되지 않는다. 409는 다른 본문뿐 아니라 같은 키의 앞선 요청이 처리 중일 때도 오므로 미확정으로 두고, 다음 조회에서 원거래를
   * 요청과 비교해 가린다. 그 밖의 4xx도 판단할 수 없어 미확정으로 둔다.
   */
  private static Result rejectedOrUnconfirmed(HttpClientErrorException e) {
    if (HttpStatus.resolve(e.getStatusCode().value()) == HttpStatus.BAD_REQUEST) {
      return new Result.Rejected("HTTP " + e.getStatusCode().value());
    }
    return new Result.Unconfirmed("HTTP " + e.getStatusCode().value());
  }

  private static String problemCode(HttpClientErrorException e) {
    try {
      Problem problem = e.getResponseBodyAs(Problem.class);
      return problem == null ? null : problem.code();
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  record MockPayout(
      UUID businessKey,
      String accountRef,
      String sellerAccountRef,
      long amount,
      String scenarioVersion) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MockTransaction(
      UUID id,
      String operation,
      UUID businessKey,
      long amount,
      String status,
      String scenarioVersion,
      OffsetDateTime succeededAt,
      String fundingAccountRef) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Problem(String code) {}
}
