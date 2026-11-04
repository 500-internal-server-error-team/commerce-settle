package com.commercesettle.democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "demo.load-enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoErrorsIntegrationTest {
  @LocalServerPort int port;
  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @CsvSource({"101,0", "0,31", "1,30"})
  @DisplayName("범위 오류와 비활성 부하 요청을 공통 오류 계약으로 반환한다")
  void preservesProblemAndFieldErrors(int rate, int seconds) throws Exception {
    var response =
        post("/demo/load-runs", "{\"rate\":" + rate + ",\"seconds\":" + seconds + "}", null);
    boolean invalid = rate < 1 || rate > 100 || seconds < 1 || seconds > 30;
    assertProblem(response, invalid ? 400 : 503);
    var body = mapper.readTree(response.body());
    if (invalid) {
      assertThat(body.path("errors")).hasSize(2);
      assertThat(body.path("errors").toString()).contains("rate", "seconds");
    }
  }

  @ParameterizedTest
  @CsvSource({"'{',body", "'{\"rate\":\"PRIVATE_INVALID_VALUE\",\"seconds\":1}',rate"})
  @DisplayName("잘못된 JSON과 타입은 거절된 원문 없이 오류 위치를 반환한다")
  void rejectsMalformedBodies(String json, String field) throws Exception {
    var response = post("/demo/load-runs", json, null);
    assertProblem(response, 400);
    assertThat(mapper.readTree(response.body()).path("errors").get(0).path("field").asText())
        .isEqualTo(field);
    assertThat(response.body())
        .doesNotContain("PRIVATE_INVALID_VALUE", "JsonParseException", "java.lang");
  }

  @Test
  @DisplayName("잘못된 실행 ID와 출처 필터도 같은 문제 응답을 반환한다")
  void includesPathAndFilterErrors() throws Exception {
    var mismatch = post("/demo/load-runs/not-a-uuid/stop", "{}", null);
    assertProblem(mismatch, 400);
    assertThat(mapper.readTree(mismatch.body()).path("errors").get(0).path("field").asText())
        .isEqualTo("id");
    var forbidden = post("/demo/load-runs", "{}", "https://foreign.example");
    assertProblem(forbidden, 403);
    assertThat(mapper.readTree(forbidden.body()).path("detail").asText())
        .isEqualTo("로컬 UI 출처만 허용합니다.");
  }

  private HttpResponse<String> post(String path, String body, String origin) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (origin != null) request.header("Origin", origin);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertProblem(HttpResponse<String> response, int status) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    var body = mapper.readTree(response.body());
    assertThat(body.path("status").asInt()).isEqualTo(status);
    for (String field : new String[] {"type", "title", "detail", "instance", "code"}) {
      assertThat(body.path(field).asText()).as(field).isNotBlank();
    }
    assertThat(body.has("traceId")).isTrue();
  }
}
