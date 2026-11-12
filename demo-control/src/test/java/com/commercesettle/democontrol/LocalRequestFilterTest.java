package com.commercesettle.democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalRequestFilterTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "http:foo",
        "http:/",
        "http:///path",
        "http://localhost:4174/path",
        "http://localhost:4174?query",
        "http://localhost:4174#fragment",
        "http://user@localhost:4174",
        "http://localhost:bad",
        "null",
        "https://localhost:4174"
      })
  @DisplayName("웹 출처 형식이 아닌 URI는 예외 없이 같은 거절 응답을 반환한다")
  void rejectsMalformedOriginsWithoutEscapingTheProblemResponse(String origin) throws Exception {
    var filter = new LocalRequestFilter(new com.fasterxml.jackson.databind.ObjectMapper());
    var request = new MockHttpServletRequest("GET", "/demo/info");
    request.setServerName("127.0.0.1");
    request.setServerPort(4174);
    request.addHeader("Origin", origin);
    var response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (a, b) -> {
          throw new AssertionError("rejected origin reached controller");
        });

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString()).contains("DEMO_ORIGIN_REJECTED", "로컬 UI 출처만 허용합니다.");
  }

  @ParameterizedTest
  @CsvSource({
    "http://localhost,80",
    "http://localhost:80,80",
    "http://[::1]:4174,4174",
    "http://LOCALHOST:5173,4174"
  })
  @DisplayName("로컬 HTTP 출처는 기본 포트와 허용된 개발 포트를 구분해 허용한다")
  void acceptsLocalOriginsIncludingTheDefaultHttpPort(String origin, int port) throws Exception {
    var filter = new LocalRequestFilter(new com.fasterxml.jackson.databind.ObjectMapper());
    var request = new MockHttpServletRequest("GET", "/demo/info");
    request.setServerName("localhost");
    request.setServerPort(port);
    request.addHeader("Origin", origin);
    var response = new MockHttpServletResponse();

    filter.doFilter(
        request, response, (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(204));

    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void rejectsForeignOriginAndAcceptsLocalJson() throws Exception {
    var filter = new LocalRequestFilter(new com.fasterxml.jackson.databind.ObjectMapper());
    var request = new MockHttpServletRequest("POST", "/demo/requests");
    request.setServerName("127.0.0.1");
    request.setServerPort(4174);
    request.setContentType("application/json");
    request.addHeader("Origin", "https://unrelated.example");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (a, b) -> {
          throw new AssertionError("foreign origin reached controller");
        });
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("로컬 UI 출처만 허용합니다.");
    request.removeHeader("Origin");
    request.addHeader("Origin", "http://127.0.0.1:5173");
    response = new MockHttpServletResponse();
    filter.doFilter(
        request, response, (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(204));
    assertThat(response.getStatus()).isEqualTo(204);
  }
}
