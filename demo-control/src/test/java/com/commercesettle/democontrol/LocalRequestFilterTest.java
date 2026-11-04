package com.commercesettle.democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalRequestFilterTest {
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
