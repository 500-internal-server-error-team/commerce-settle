package com.commercesettle.internalauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@WebMvcTest(
    controllers = InternalCallerFilterTest.Endpoints.class,
    properties = {
      "app.internal-auth.service=commerce",
      "app.internal-auth.senders.gateway.current=" + InternalCallerFilterTest.GATEWAY,
      "app.internal-auth.senders.gateway.previous=" + InternalCallerFilterTest.GATEWAY_PREVIOUS,
      // 인라인 속성은 ':'도 구분자로 읽으므로 발신자 ID의 ':'를 이스케이프한다.
      "app.internal-auth.senders[service\\:payment].current=" + InternalCallerFilterTest.PAYMENT,
      "app.internal-auth.senders[operator\\:ops-1].current=" + InternalCallerFilterTest.OPERATOR
    })
@Import({
  InternalAuthAutoConfiguration.class,
  InternalCallerFilterTest.Endpoints.class,
  InternalCallerFilterTest.Errors.class,
  InternalCallerFilterTest.Meters.class
})
@ExtendWith(OutputCaptureExtension.class)
class InternalCallerFilterTest {
  static final String GATEWAY = "gateway-credential-0123456789abcdef0123456789";
  static final String GATEWAY_PREVIOUS = "gateway-previous-0123456789abcdef0123456789ab";
  static final String PAYMENT = "payment-credential-0123456789abcdef0123456789";
  static final String OPERATOR = "operator-credential-0123456789abcdef012345678";
  private static final String OTHER_TARGET = "payment-target-credential-0123456789abcdef01";
  private static final String ORDER_CONTEXT =
      "/internal/orders/00000000-0000-4000-8000-000000000001/payment-context";

  @Autowired private MockMvc mvc;
  @Autowired private MeterRegistry meters;

  @ParameterizedTest
  @ValueSource(strings = {GATEWAY, GATEWAY_PREVIOUS})
  @DisplayName("교체 중에는 Gateway의 현재·이전 자격을 모두 허용한다")
  void acceptsCurrentAndPreviousGatewayCredential(String credential) throws Exception {
    mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
        .andExpect(status().isOk())
        .andExpect(content().string("products"));
  }

  @Test
  @DisplayName("Gateway 자격의 보호 작업 요청은 신원 헤더와 함께 컨트롤러에 도달한다")
  void passesGatewayIdentityHeaders() throws Exception {
    mvc.perform(
            gateway(get("/api/accounts/me"))
                .header("X-Authenticated-Actor-Kind", "BUYER")
                .header("X-Authenticated-Actor-Id", "00000000-0000-4000-8000-000000000001"))
        .andExpect(status().isOk())
        .andExpect(content().string("BUYER"));
  }

  @Test
  @DisplayName("서비스·운영자 자격은 명세의 자기 작업만 호출한다")
  void acceptsServiceAndOperatorOnTheirOperations() throws Exception {
    mvc.perform(get(ORDER_CONTEXT).header(HttpHeaders.AUTHORIZATION, "Bearer " + PAYMENT))
        .andExpect(status().isOk());
    mvc.perform(
            get("/internal/coupon-issue-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR))
        .andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "Basic Z2F0ZXdheQ==",
        "Bearer ",
        "Bearer wrong-credential-0123456789abcdef0123456789",
        "Bearer " + OTHER_TARGET
      })
  @DisplayName("자격이 없거나 형식·값이 다르면 401 공통 오류다")
  void rejectsMissingOrUnknownCredential(String authorization, CapturedOutput output)
      throws Exception {
    final var request = get("/api/products");
    if (!authorization.isEmpty()) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }

    mvc.perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401));

    assertThat(output).doesNotContain(OTHER_TARGET).doesNotContain(GATEWAY);
  }

  @Test
  @DisplayName("Authorization이 여러 개면 401이다")
  void rejectsDuplicatedAuthorization() throws Exception {
    mvc.perform(
            get("/api/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY, "Bearer " + PAYMENT))
        .andExpect(status().isUnauthorized());
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"POST,/api/products", "GET,/api/unregistered", "GET,/api/products/"})
  @DisplayName("명세에 없는 메서드·경로 조합은 유효한 자격이어도 404다")
  void rejectsOperationOutsideContract(String method, String path) throws Exception {
    final var request = "POST".equals(method) ? post(path) : get(path);

    mvc.perform(gateway(request)).andExpect(status().isNotFound());
  }

  @ParameterizedTest(name = "{1} {2}")
  @CsvSource({
    "gateway,GET," + ORDER_CONTEXT,
    "gateway,GET,/api/payments",
    "payment,GET,/internal/products/00000000-0000-4000-8000-000000000001/settlement-context",
    "payment,GET,/api/products",
    "operator,GET,/api/products",
    "operator,GET," + ORDER_CONTEXT
  })
  @DisplayName("다른 서비스 소유 작업이나 발신자와 맞지 않는 작업은 403이다")
  void rejectsOperationOfOtherCaller(String sender, String method, String path) throws Exception {
    final var credential =
        switch (sender) {
          case "gateway" -> GATEWAY;
          case "payment" -> PAYMENT;
          default -> OPERATOR;
        };

    mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Gateway가 아닌 발신자가 신원 헤더를 보내면 403이다")
  void rejectsIdentityHeaderFromNonGateway() throws Exception {
    mvc.perform(
            get(ORDER_CONTEXT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAYMENT)
                .header("x-authenticated-actor-id", "00000000-0000-4000-8000-000000000001"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("명세 문서는 자격 없이 조회한다")
  void allowsApiDocumentWithoutCredential() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("판정 실패를 발신자와 상태로 집계한다")
  void countsRejectionsBySenderAndStatus() throws Exception {
    final var before = rejections("unknown", "401");

    mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAYMENT))
        .andExpect(status().isForbidden());

    assertThat(rejections("unknown", "401")).isEqualTo(before + 1);
    assertThat(rejections("service:payment", "403")).isPositive();
  }

  private double rejections(String sender, String status) {
    final var counter =
        meters.find("internal.auth.rejections").tags("sender", sender, "status", status).counter();
    return counter == null ? 0 : counter.count();
  }

  private static MockHttpServletRequestBuilder gateway(MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY);
  }

  @RestController
  static class Endpoints {
    @GetMapping("/api/products")
    String products() {
      return "products";
    }

    @GetMapping("/api/accounts/me")
    String me(@RequestHeader("X-Authenticated-Actor-Kind") String kind) {
      return kind;
    }

    @GetMapping("/internal/orders/{orderId}/payment-context")
    String paymentContext() {
      return "context";
    }

    @GetMapping("/internal/coupon-issue-runs")
    String couponIssueRuns() {
      return "runs";
    }

    @GetMapping("/v3/api-docs")
    String document() {
      return "{}";
    }

    @PostMapping("/api/products")
    String unregistered() {
      return "unregistered";
    }
  }

  /** 오류 형식은 각 서비스의 예외 처리기가 정한다. 여기서는 판정 결과가 MVC 예외 처리로 넘어가는지만 본다. */
  @RestControllerAdvice
  static class Errors extends ResponseEntityExceptionHandler {}

  @TestConfiguration(proxyBeanMethods = false)
  static class Meters {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
