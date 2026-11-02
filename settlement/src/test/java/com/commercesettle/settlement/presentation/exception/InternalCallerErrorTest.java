package com.commercesettle.settlement.presentation.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.internalauth.InternalAuthAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 첫 컨트롤러 전에 둔 내부 호출 자격 판정이 공통 오류 형식으로 응답하는지 확인한다. 판정 분기 전체는 internal-auth 모듈 시험이 맡는다. */
@WebMvcTest(
    controllers = InternalCallerErrorTest.Endpoints.class,
    properties = {
      "app.internal-auth.service=settlement",
      "app.internal-auth.senders.gateway.current=" + InternalCallerErrorTest.GATEWAY,
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
    })
@Import({InternalAuthAutoConfiguration.class, InternalCallerErrorTest.Endpoints.class})
class InternalCallerErrorTest {
  static final String GATEWAY = "gateway-credential-0123456789abcdef0123456789";
  private static final String OTHER_TARGET = "commerce-gateway-credential-0123456789abcdef";
  private static final String OPERATION = "/api/sellers/me/payouts";
  private static final String ACTOR = "SELLER";

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("Gateway 자격의 작업 요청은 신원 헤더와 함께 컨트롤러에 도달한다")
  void passesGatewayCredential() throws Exception {
    mvc.perform(
            get(OPERATION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY)
                .header("X-Authenticated-Actor-Kind", ACTOR))
        .andExpect(status().isOk())
        .andExpect(content().string(ACTOR));
  }

  @ParameterizedTest(name = "{1} → {2}")
  @CsvSource({
    "''," + OPERATION + ",401,COMMON_AUTHENTICATION_REQUIRED",
    OTHER_TARGET + "," + OPERATION + ",401,COMMON_AUTHENTICATION_REQUIRED",
    GATEWAY + ",/api/unregistered,404,COMMON_RESOURCE_NOT_FOUND",
    GATEWAY + ",/api/products,403,COMMON_ROLE_FORBIDDEN"
  })
  @DisplayName("판정 실패는 공통 오류 형식으로 응답한다")
  void rejectsWithCommonProblem(String credential, String path, int status, String code)
      throws Exception {
    final var request = get(path);
    if (!credential.isEmpty()) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
    }

    mvc.perform(request)
        .andExpect(status().is(status))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.instance").value(path));
  }

  @RestController
  static class Endpoints {
    @GetMapping(OPERATION)
    String operation(@RequestHeader("X-Authenticated-Actor-Kind") String kind) {
      return kind;
    }
  }
}
