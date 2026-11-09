package com.commercesettle.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class SwaggerFixtureConfigurationTest {
  private static final String PASSWORD = "synthetic-fixture-password";
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<ClientRequest> forwarded = new AtomicReference<>();
  private final AtomicReference<String> forwardedBody = new AtomicReference<>();

  private WebTestClient client(HttpStatus status) {
    final var builder =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  forwarded.set(request);
                  final var body = new MockClientHttpRequest(request.method(), request.url());
                  return request
                      .writeTo(body, ExchangeStrategies.withDefaults())
                      .then(Mono.defer(body::getBodyAsString))
                      .doOnNext(forwardedBody::set)
                      .map(
                          ignored ->
                              ClientResponse.create(status)
                                  .header(
                                      HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                  .header(HttpHeaders.RETRY_AFTER, "30")
                                  .body("{\"token\":\"synthetic-session\"}")
                                  .build());
                });
    return WebTestClient.bindToRouterFunction(
            new SwaggerFixtureConfiguration()
                .swaggerFixtureSessions(
                    builder,
                    new UpstreamCredentials(
                        Map.of(
                            "commerce",
                            "c".repeat(43),
                            "payment",
                            "p".repeat(43),
                            "settlement",
                            "s".repeat(43))),
                    "http://commerce:8080",
                    PASSWORD))
        .configureClient()
        .baseUrl("http://localhost")
        .build();
  }

  @ParameterizedTest
  @CsvSource({
    "BUYER_A, buyer, fixture-buyer-a@example.test",
    "BUYER_B, buyer, fixture-buyer-b@example.test",
    "SELLER_A, seller, fixture-seller-a@example.test",
    "SELLER_B, seller, fixture-seller-b@example.test"
  })
  @DisplayName("선택한 고정 계정만 서버 비밀번호로 기존 로그인 API에 전달한다")
  void logsInOnlyTheSelectedFixture(String account, String kind, String email) throws Exception {
    client(HttpStatus.OK)
        .post()
        .uri("/swagger-ui/fixture-sessions")
        .header(HttpHeaders.ORIGIN, "http://localhost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("account", account))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectBody()
        .json("{\"token\":\"synthetic-session\"}");

    assertThat(forwarded.get().url().getPath()).isEqualTo("/api/" + kind + "-sessions");
    assertThat(forwarded.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer " + "c".repeat(43));
    assertThat(mapper.readTree(forwardedBody.get()))
        .isEqualTo(mapper.valueToTree(Map.of("email", email, "password", PASSWORD)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://other.test", "null", ""})
  @DisplayName("다른 출처와 출처 없는 간편 로그인은 상류에 전달하지 않는다")
  void rejectsOtherOrigins(String origin) {
    client(HttpStatus.OK)
        .post()
        .uri("/swagger-ui/fixture-sessions")
        .header(HttpHeaders.ORIGIN, origin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("account", "BUYER_A"))
        .exchange()
        .expectStatus()
        .isForbidden();

    assertThat(forwarded.get()).isNull();
  }

  @Test
  @DisplayName("임의 이메일이나 등록되지 않은 계정으로 간편 로그인하지 않는다")
  void rejectsUnknownAccount() {
    client(HttpStatus.OK)
        .post()
        .uri("/swagger-ui/fixture-sessions")
        .header(HttpHeaders.ORIGIN, "http://localhost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("account", "owner@example.test"))
        .exchange()
        .expectStatus()
        .isBadRequest();

    assertThat(forwarded.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 429, 503})
  @DisplayName("기존 로그인 API의 인증 거절과 요청 제한 및 장애 응답을 유지한다")
  void preservesLoginFailures(int status) {
    client(HttpStatus.valueOf(status))
        .post()
        .uri("/swagger-ui/fixture-sessions")
        .header(HttpHeaders.ORIGIN, "http://localhost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("account", "BUYER_A"))
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectHeader()
        .valueEquals(HttpHeaders.RETRY_AFTER, "30");
  }

  @Test
  @DisplayName("개발 비밀번호가 없으면 간편 로그인 서버를 시작하지 않는다")
  void requiresFixturePassword() {
    assertThatThrownBy(
            () ->
                new SwaggerFixtureConfiguration()
                    .swaggerFixtureSessions(WebClient.builder(), null, "http://commerce:8080", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DEV_FIXTURE_PASSWORD");
  }
}
