package com.commercesettle.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway.RefundRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRefundGatewayTest {
  private static final String CREDENTIAL = "payment-finance-mock-credential-0123456789ab";
  private static final UUID ORIGINAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID EXTERNAL = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final RefundRequest REQUEST =
      new RefundRequest("PG_A", ORIGINAL, KEY, 8000, "original-v1");
  private static final String POST =
      "http://mock/mock/providers/PG_A/payments/" + ORIGINAL + "/refunds";
  private static final String LOOKUP =
      "http://mock/mock/providers/PG_A/transactions/"
          + KEY
          + "?operation=REFUND&scenarioVersion=original-v1";
  private MockRestServiceServer server;
  private HttpRefundGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://mock");
    server = MockRestServiceServer.bindTo(builder).build();
    gateway = new HttpRefundGateway(builder.build(), CREDENTIAL);
  }

  @Test
  @DisplayName("환불은 원승인 ID·원키·외부 금액만 보내고 조회도 같은 환불과 원버전을 사용한다")
  void preservesOriginalRequestAndLookup() {
    server
        .expect(requestTo(POST))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + CREDENTIAL))
        .andExpect(header("Idempotency-Key", "refund-" + KEY))
        .andExpect(content().json("{\"businessKey\":\"" + KEY + "\",\"amount\":8000}", true))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transaction()));
    server
        .expect(requestTo(LOOKUP))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + CREDENTIAL))
        .andRespond(
            withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(transaction()));

    assertThat(gateway.refund(REQUEST)).isInstanceOf(Result.Succeeded.class);
    assertThat(gateway.lookup(REQUEST)).isEqualTo(gatewayResult());
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({
    "providerId,PG_B",
    "businessKey,00000000-0000-0000-0000-0000000000c2",
    "originalId,00000000-0000-0000-0000-0000000000a2",
    "amount,10000",
    "operation,APPROVE",
    "scenarioVersion,other-v1"
  })
  @DisplayName("다른 원거래·원버전·키·금액의 응답은 성공이나 확정 거절로 믿지 않는다")
  void rejectsMismatchedResponse(String field, String value) throws Exception {
    ObjectNode body = (ObjectNode) new ObjectMapper().readTree(transaction());
    if (field.equals("amount")) body.put(field, Long.parseLong(value));
    else body.put(field, value);
    server
        .expect(requestTo(LOOKUP))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()));

    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.Rejected.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROCESSING", "UNKNOWN"})
  @DisplayName("처리 중과 알 수 없는 상태는 미확정이다")
  void keepsNonterminalResultsUnconfirmed(String status) {
    server
        .expect(requestTo(POST))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transaction().replace("SUCCEEDED", status)));

    assertThat(gateway.refund(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("일치하는 거래의 DECLINED만 확정 거절이다")
  void confirmsDeclinedTransaction() {
    server
        .expect(requestTo(LOOKUP))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transaction().replace("SUCCEEDED", "DECLINED")));

    assertThat(gateway.lookup(REQUEST)).isEqualTo(new Result.Declined(EXTERNAL));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 409, 500, 503, 504})
  @DisplayName("자격 거절·충돌·원승인 부재·서버 장애·유실은 확정 실패가 아니다")
  void keepsHttpFailuresUnconfirmed(int status) {
    server.expect(requestTo(POST)).andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThat(gateway.refund(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("조회에서 거래 부재 코드가 확인될 때만 NotFound다")
  void requiresConfirmedAbsenceForResend() {
    server
        .expect(requestTo(LOOKUP))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"code\":\"MOCK_TRANSACTION_NOT_FOUND\"}"));
    server
        .expect(requestTo(LOOKUP))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"code\":\"OTHER_NOT_FOUND\"}"));

    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.NotFound.class);
    assertThat(gateway.lookup(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "status", "succeededAt"})
  @DisplayName("성공 증빙 필드가 없으면 미확정이다")
  void requiresSuccessEvidence(String field) throws Exception {
    ObjectNode body = (ObjectNode) new ObjectMapper().readTree(transaction());
    body.remove(field);
    server
        .expect(requestTo(POST))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()));

    assertThat(gateway.refund(REQUEST)).isInstanceOf(Result.Unconfirmed.class);
  }

  @Test
  @DisplayName("HTTP 환불도 Finance Mock 호출 자격이 필요하다")
  void requiresCredential() {
    assertThatThrownBy(() -> new HttpRefundGateway(RestClient.create(), ""))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Result gatewayResult() {
    return new Result.Succeeded(EXTERNAL, java.time.Instant.parse("2026-11-03T01:00:00Z"));
  }

  private static String transaction() {
    return """
        {"id":"%s","providerId":"PG_A","operation":"REFUND","businessKey":"%s",
         "originalId":"%s","amount":8000,"status":"SUCCEEDED","scenarioVersion":"original-v1",
         "succeededAt":"2026-11-03T10:00:00+09:00"}
        """
        .formatted(EXTERNAL, KEY, ORIGINAL);
  }
}
