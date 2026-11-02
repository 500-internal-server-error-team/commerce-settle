package com.commercesettle.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpPaymentGatewayTest {
  private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final String CREDENTIAL = "payment-finance-mock-credential-0123456789ab";
  private static final UUID EXTERNAL = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final ApprovalRequest REQUEST =
      new ApprovalRequest(
          "PG_A", KEY, "CREDIT_CARD", "card-ref", "payout-funding-default", 8_000, "pg-normal-v1");

  private MockRestServiceServer server;
  private HttpPaymentGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://mock");
    server = MockRestServiceServer.bindTo(builder).build();
    gateway = new HttpPaymentGateway(builder.build(), CREDENTIAL);
  }

  @Test
  @DisplayName("승인은 원키 멱등 헤더와 고정한 본문으로 보내고, 대역의 성공을 승인으로 해석한다")
  void approvesWithIdempotencyKeyAndFixedBody() {
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + CREDENTIAL))
        .andExpect(header("Idempotency-Key", "approve-" + KEY))
        .andExpect(jsonPath("$.businessKey").value(KEY.toString()))
        .andExpect(jsonPath("$.method").value("CREDIT_CARD"))
        .andExpect(jsonPath("$.accountRef").value("card-ref"))
        .andExpect(jsonPath("$.amount").value(8_000))
        .andExpect(jsonPath("$.scenarioVersion").value("pg-normal-v1"))
        .andExpect(jsonPath("$.fundingAccountRef").value("payout-funding-default"))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .body(transaction("SUCCEEDED", 8_000))
                .contentType(MediaType.APPLICATION_JSON));

    assertThat(gateway.approve(REQUEST)).isInstanceOf(Result.Succeeded.class);
    server.verify();
  }

  @Test
  @DisplayName("대역의 확정 거절은 거절로, 처리 중은 미확정으로 해석한다")
  void interpretsDeclinedAndProcessing() {
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .body(transaction("DECLINED", 8_000))
                .contentType(MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .body(transaction("PROCESSING", 8_000))
                .contentType(MediaType.APPLICATION_JSON));

    assertThat(gateway.approve(REQUEST)).isEqualTo(new Result.Declined(EXTERNAL));
    assertThat(gateway.approve(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("응답 유실(504)은 실패가 아니라 미확정이다")
  void treatsLostResponseAsUnconfirmed() {
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

    assertThat(gateway.approve(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("요청과 다른 금액의 원거래는 믿지 않고 운영 확인으로 넘긴다")
  void rejectsMismatchedTransaction() {
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .body(transaction("SUCCEEDED", 9_000))
                .contentType(MediaType.APPLICATION_JSON));

    assertThat(gateway.approve(REQUEST)).isInstanceOf(Result.Rejected.class);
  }

  @Test
  @DisplayName("조회 404는 거래 부재 코드일 때만 거래 없음이고, 그 밖의 404는 미확정이다")
  void treatsOnlyTransactionNotFoundCodeAsMissing() {
    String lookup =
        "http://mock/mock/providers/PG_A/transactions/"
            + KEY
            + "?operation=APPROVE&scenarioVersion=pg-normal-v1";
    server
        .expect(requestTo(lookup))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + CREDENTIAL))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"code\":\"MOCK_TRANSACTION_NOT_FOUND\"}"));
    server.expect(requestTo(lookup)).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.NotFound.class);
    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("자격 거절(401·403)은 대역이 처리하지 않은 것이라 미확정이다")
  void treatsCredentialRejectionAsUnconfirmed() {
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    server
        .expect(requestTo("http://mock/mock/providers/PG_A/payments"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThat(gateway.approve(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
    assertThat(gateway.approve(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("finance-mock 자격이 없거나 짧으면 만들지 않는다")
  void requiresFinanceMockCredential() {
    RestClient client = RestClient.builder().baseUrl("http://mock").build();

    assertThatThrownBy(() -> new HttpPaymentGateway(client, ""))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HttpPaymentGateway(client, "short"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static String transaction(String status, long amount) {
    return """
        {"id":"%s","providerId":"PG_A","operation":"APPROVE","businessKey":"%s","amount":%d,
         "status":"%s","scenarioVersion":"pg-normal-v1","fundingAccountRef":"payout-funding-default",
         "succeededAt":%s}
        """
        .formatted(
            EXTERNAL,
            KEY,
            amount,
            status,
            "SUCCEEDED".equals(status) ? "\"2026-11-02T10:00:00+09:00\"" : "null");
  }
}
