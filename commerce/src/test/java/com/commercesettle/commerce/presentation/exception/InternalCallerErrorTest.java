package com.commercesettle.commerce.presentation.exception;

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
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
    controllers = InternalCallerErrorTest.Endpoints.class,
    properties = {
      "app.internal-auth.service=commerce",
      "app.internal-auth.senders.gateway.current=" + InternalCallerErrorTest.GATEWAY
    })
@Import({InternalAuthAutoConfiguration.class, InternalCallerErrorTest.Endpoints.class})
class InternalCallerErrorTest {
  static final String GATEWAY = "gateway-credential-0123456789abcdef0123456789";

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("Gateway 자격의 커머스 작업은 컨트롤러에 도달한다")
  void passesGatewayCredential() throws Exception {
    mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY))
        .andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{1} → {2}")
  @CsvSource({
    "'',/api/products,401,COMMON_AUTHENTICATION_REQUIRED",
    GATEWAY + ",/api/unregistered,404,COMMON_RESOURCE_NOT_FOUND",
    GATEWAY + ",/api/payments,403,COMMON_ROLE_FORBIDDEN"
  })
  @DisplayName("판정 실패는 커머스 공통 오류 형식으로 응답한다")
  void rejectsWithCommonProblem(String credential, String path, int status, String code)
      throws Exception {
    final var request = get(path);
    if (!credential.isEmpty()) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
    }

    mvc.perform(request)
        .andExpect(status().is(status))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.instance").value(path));
  }

  @RestController
  static class Endpoints {
    @GetMapping("/api/products")
    String products() {
      return "products";
    }
  }
}
