package com.commercesettle.settlement.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.commercesettle.settlement.application.port.PayoutGateway.PayoutRequest;
import com.commercesettle.settlement.application.port.PayoutGateway.Result;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpPayoutGatewayTest {
  private static final UUID KEY = UUID.fromString("5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a");
  private static final UUID EXTERNAL = UUID.fromString("0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9");
  private static final PayoutRequest REQUEST =
      new PayoutRequest(KEY, "payout-funding-default", "seller-account", 25_500, "payout-v1");

  private MockRestServiceServer server;
  private HttpPayoutGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://finance-mock");
    server = MockRestServiceServer.bindTo(builder).build();
    gateway = new HttpPayoutGateway(builder.build(), "lookup-v1");
  }

  @Test
  @DisplayName("실행 요청은 고정한 계정·금액·시나리오와 업무 키를 보내고, 성공 응답의 최초 성공 시각을 돌려준다")
  void executeReturnsProviderSuccess() {
    server
        .expect(requestTo("http://finance-mock/mock/payouts"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "payout-" + KEY))
        .andExpect(jsonPath("$.businessKey").value(KEY.toString()))
        .andExpect(jsonPath("$.accountRef").value("payout-funding-default"))
        .andExpect(jsonPath("$.sellerAccountRef").value("seller-account"))
        .andExpect(jsonPath("$.amount").value(25_500))
        .andExpect(jsonPath("$.scenarioVersion").value("payout-v1"))
        .andRespond(transaction(HttpStatus.ACCEPTED, KEY, 25_500, "SUCCEEDED"));

    Result result = gateway.execute(REQUEST);

    assertThat(result)
        .isEqualTo(new Result.Succeeded(EXTERNAL, Instant.parse("2026-10-30T01:00:03.123456Z")));
  }

  @Test
  @DisplayName("확정 거절은 거절로, 처리 중은 미확정으로 돌려준다")
  void interpretsDeclinedAndProcessing() {
    server
        .expect(requestTo("http://finance-mock/mock/payouts"))
        .andRespond(transaction(HttpStatus.ACCEPTED, KEY, 25_500, "DECLINED"));
    server
        .expect(requestTo("http://finance-mock/mock/payouts/" + KEY + "?scenarioVersion=lookup-v1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(transaction(HttpStatus.OK, KEY, 25_500, "PROCESSING"));

    assertThat(gateway.execute(REQUEST)).isEqualTo(new Result.Declined(EXTERNAL));
    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("응답 유실(504)과 조회 장애(503)는 실패가 아니라 미확정이다")
  void serverErrorsAreUnconfirmed() {
    server
        .expect(requestTo("http://finance-mock/mock/payouts"))
        .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
    server
        .expect(requestTo("http://finance-mock/mock/payouts/" + KEY + "?scenarioVersion=lookup-v1"))
        .andRespond(problem(HttpStatus.SERVICE_UNAVAILABLE, "MOCK_LOOKUP_FAILED"));

    assertThat(gateway.execute(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("조회 404는 대역이 거래 부재 코드로 답했을 때만 거래 부재다")
  void onlyTransactionNotFoundCodeMeansMissing() {
    String lookupUri = "http://finance-mock/mock/payouts/" + KEY + "?scenarioVersion=lookup-v1";
    server
        .expect(requestTo(lookupUri))
        .andRespond(problem(HttpStatus.NOT_FOUND, "MOCK_TRANSACTION_NOT_FOUND"));
    server.expect(requestTo(lookupUri)).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(gateway.lookup(REQUEST)).isEqualTo(new Result.NotFound());
    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("본문 충돌과 원거래와 다른 응답은 재시도로 풀리지 않아 운영 확인 대상이다")
  void conflictAndMismatchAreRejected() {
    server
        .expect(requestTo("http://finance-mock/mock/payouts"))
        .andRespond(problem(HttpStatus.CONFLICT, "COMMON_IDEMPOTENCY_CONFLICT"));
    server
        .expect(requestTo("http://finance-mock/mock/payouts"))
        .andRespond(transaction(HttpStatus.ACCEPTED, KEY, 30_000, "SUCCEEDED"));

    assertThat(gateway.execute(REQUEST)).isInstanceOf(Result.Rejected.class);
    assertThat(gateway.execute(REQUEST)).isInstanceOf(Result.Rejected.class);
  }

  private static org.springframework.test.web.client.ResponseCreator transaction(
      HttpStatus status, UUID businessKey, long amount, String transactionStatus) {
    String succeededAt =
        "SUCCEEDED".equals(transactionStatus) ? "\"2026-10-30T10:00:03.123456+09:00\"" : "null";
    String body =
        """
        {"id":"%s","providerId":"PAYOUT","operation":"PAYOUT","businessKey":"%s",\
        "originalId":null,"amount":%d,"status":"%s","scenarioVersion":"payout-v1",\
        "createdAt":"2026-10-30T10:00:00+09:00","succeededAt":%s,"pgReceiptDueAt":null,\
        "pgReceivedAt":null,"pgReceivedAmount":null,"fundingAccountRef":"payout-funding-default"}"""
            .formatted(EXTERNAL, businessKey, amount, transactionStatus, succeededAt);
    return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  private static org.springframework.test.web.client.ResponseCreator problem(
      HttpStatus status, String code) {
    return withStatus(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body("{\"status\":%d,\"code\":\"%s\"}".formatted(status.value(), code));
  }
}
