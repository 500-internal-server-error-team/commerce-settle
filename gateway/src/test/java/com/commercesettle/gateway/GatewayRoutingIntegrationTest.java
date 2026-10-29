package com.commercesettle.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.commercesettle.gateway.filter.SessionVerification;
import com.commercesettle.gateway.filter.SessionVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.zipkin.tracing.export.enabled=false",
      "gateway.auth.session-epoch=00000000-0000-4000-8000-0000000000aa"
    })
@Import(GatewayRoutingIntegrationTest.FailureRoutes.class)
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureObservability(metrics = false)
class GatewayRoutingIntegrationTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, Backend> BACKENDS =
      Map.of(
          "commerce", backend("commerce"),
          "payment", backend("payment"),
          "settlement", backend("settlement"));
  private static final JsonNode CONTRACT = contract();
  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String TOKEN = "session-token-probe";
  private static final String TOKEN_HASH = sha256(TOKEN);
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

  @Autowired private WebTestClient client;
  @MockitoBean private SessionVerifier verifier;

  @BeforeEach
  void authenticateAsBuyer() {
    given(verifier.verify(anyString()))
        .willReturn(Mono.just(SessionVerification.authenticated("BUYER", ACCOUNT_ID)));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    BACKENDS.forEach(
        (name, backend) ->
            registry.add(
                "gateway.services." + name, () -> "http://127.0.0.1:" + backend.server().port()));
  }

  @AfterAll
  static void stopBackends() {
    BACKENDS.values().forEach(backend -> backend.server().disposeNow());
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @MethodSource("externalOperations")
  @DisplayName("외부 API를 명세의 소유 서비스로 전달한다")
  void routesEveryExternalOperation(String method, String path, String owner, String actor) {
    if (!"PUBLIC".equals(actor)) {
      given(verifier.verify(anyString()))
          .willReturn(Mono.just(SessionVerification.authenticated(actor, ACCOUNT_ID)));
    }

    client
        .method(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)))
        .uri(concretePath(path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.service")
        .isEqualTo(owner)
        .jsonPath("$.path")
        .isEqualTo(concretePath(path));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("internalOperations")
  @DisplayName("내부 API와 Mock은 외부로 전달하지 않는다")
  void rejectsEveryInternalOperation(String method, String path) {
    client
        .method(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)))
        .uri(concretePath(path))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("COMMON_RESOURCE_NOT_FOUND");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/not-declared",
        "/api/sellers/me/not-declared",
        "/api/settlements",
        "/actuator/health",
        "/actuator/env",
        "/actuator/prometheus",
        "/api/mock/approve",
        "/api/accounts/me/"
      })
  @DisplayName("미등록 경로와 관리 경로를 노출하지 않는다")
  void rejectsUnlistedPaths(String path) {
    client
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(404)
        .jsonPath("$.traceId")
        .isNotEmpty();
  }

  @Test
  @DisplayName("명세에 없는 HTTP 메서드를 같은 경로로 전달하지 않는다")
  void rejectsUnlistedMethod() {
    client.post().uri("/api/products").exchange().expectStatus().isNotFound();
  }

  @Test
  @DisplayName("본문과 쿼리 및 멱등 키를 그대로 전달한다")
  void preservesRequest() {
    final var body = "{\"quantity\":2}";

    client
        .post()
        .uri("/api/orders?probe=one&probe=two")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", "gateway-routing-probe")
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk();

    final var request = BACKENDS.get("commerce").last().get();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.query()).isEqualTo("probe=one&probe=two");
    assertThat(request.idempotencyKey()).isEqualTo("gateway-routing-probe");
    assertThat(request.body()).isEqualTo(body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"commerce", "payment", "settlement"})
  @DisplayName("기존 서비스 명세 경로를 유지한다")
  void preservesDocumentationRoutes(String service) {
    client
        .get()
        .uri("/v3/api-docs/" + service)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.service")
        .isEqualTo(service)
        .jsonPath("$.path")
        .isEqualTo("/v3/api-docs");
  }

  @Test
  @DisplayName("Swagger UI를 유지한다")
  void preservesSwaggerUi() {
    client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
  }

  @Test
  @DisplayName("백엔드 오류 응답의 계약과 추적 ID를 바꾸지 않는다")
  void preservesBackendProblem() {
    client
        .get()
        .uri("/api/payments?mode=problem")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("PAYMENT_PROBE_CONFLICT")
        .jsonPath("$.traceId")
        .isEqualTo("backend-trace-probe");
  }

  @Test
  @DisplayName("Gateway 오류에 실제 추적 ID를 넣고 쿼리를 노출하지 않는다")
  void rendersProblemWithActualTrace() {
    client
        .get()
        .uri("/api/not-declared?token=private-probe")
        .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("urn:commerce-settle:error:common-resource-not-found")
        .jsonPath("$.title")
        .isNotEmpty()
        .jsonPath("$.detail")
        .isNotEmpty()
        .jsonPath("$.instance")
        .isEqualTo("/api/not-declared")
        .jsonPath("$.code")
        .isEqualTo("COMMON_RESOURCE_NOT_FOUND")
        .jsonPath("$.traceId")
        .isEqualTo(TRACE_ID)
        .consumeWith(
            result ->
                assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8))
                    .doesNotContain("private-probe", "stackTrace", "exception"));
  }

  @Test
  @DisplayName("예상 밖 오류 메시지에 포함된 내부 정보를 숨긴다")
  void sanitizesUnexpectedFailure(CapturedOutput output) {
    client
        .get()
        .uri("/gateway-test/failure?token=private-query-probe")
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(500)
        .jsonPath("$.code")
        .isEqualTo("COMMON_INTERNAL_ERROR")
        .jsonPath("$.detail")
        .value(value -> assertThat(value.toString()).doesNotContain("private-probe", "127.0.0.1"));

    assertThat(output.getAll()).doesNotContain("private-probe", "private-query-probe");
  }

  @Test
  @DisplayName("상류 시간 초과를 공통 503 계약으로 응답한다")
  void mapsUpstreamTimeout() {
    client
        .get()
        .uri("/gateway-test/timeout")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(503)
        .jsonPath("$.code")
        .isEqualTo("COMMON_TEMPORARILY_UNAVAILABLE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"reset", "abort"})
  @DisplayName("상류 연결 종료를 클라이언트 종료로 오인하지 않는다")
  void rendersConnectionFailure(String failure, CapturedOutput output) {
    client
        .get()
        .uri("/gateway-test/" + failure + "?token=private-query-probe")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(503)
        .jsonPath("$.code")
        .isEqualTo("COMMON_TEMPORARILY_UNAVAILABLE")
        .jsonPath("$.traceId")
        .isNotEmpty();

    assertThat(output.getAll()).doesNotContain("private-probe", "private-query-probe");
  }

  @Test
  @DisplayName("응답 전 연결이 끊겨도 요청을 자동 재전송하지 않는다")
  void doesNotRetryDisconnectedRequest() {
    final var count = BACKENDS.get("commerce").count().get();

    client
        .post()
        .uri("/api/orders?mode=disconnect")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .bodyValue("probe")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("COMMON_TEMPORARILY_UNAVAILABLE");

    assertThat(BACKENDS.get("commerce").count().get()).isEqualTo(count + 1);
  }

  @Test
  @DisplayName("보호 작업에는 위조 신원 헤더와 세션 토큰 대신 검증한 신원만 전달한다")
  void forwardsVerifiedIdentityOnly() {
    client
        .get()
        .uri("/api/payments")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .header("X-Authenticated-Actor-Kind", "SELLER")
        .header("X-Authenticated-Actor-Id", UUID.randomUUID().toString())
        .header("X-Authenticated-Session-Hash", "forged")
        .header("x-authenticated-extra", "forged")
        .exchange()
        .expectStatus()
        .isOk();

    final var headers = BACKENDS.get("payment").last().get().headers();
    assertThat(headers.get("X-Authenticated-Actor-Kind")).isEqualTo("BUYER");
    assertThat(headers.get("X-Authenticated-Actor-Id")).isEqualTo(ACCOUNT_ID.toString());
    assertThat(headers.names())
        .noneMatch(name -> name.equalsIgnoreCase("X-Authenticated-Session-Hash"))
        .noneMatch(name -> name.equalsIgnoreCase("X-Authenticated-Extra"))
        .noneMatch(name -> name.equalsIgnoreCase(HttpHeaders.AUTHORIZATION));
    then(verifier).should().verify(TOKEN_HASH);
  }

  @Test
  @DisplayName("로그아웃에는 검증한 세션의 해시를 함께 전달한다")
  void forwardsSessionHashOnLogout() {
    client
        .delete()
        .uri("/api/sessions/current")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(BACKENDS.get("commerce").last().get().headers().get("X-Authenticated-Session-Hash"))
        .isEqualTo(TOKEN_HASH);
  }

  @Test
  @DisplayName("공개 작업은 세션을 확인하지 않고 클라이언트의 인증 헤더를 지운다")
  void stripsAuthenticationHeadersOnPublicOperation() {
    client
        .get()
        .uri("/api/products")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .header("X-Authenticated-Actor-Kind", "SELLER")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(BACKENDS.get("commerce").last().get().headers().names())
        .noneMatch(name -> name.equalsIgnoreCase("X-Authenticated-Actor-Kind"))
        .noneMatch(name -> name.equalsIgnoreCase(HttpHeaders.AUTHORIZATION));
    then(verifier).shouldHaveNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Basic c2VsbGVy", "Bearer ", "Bearer two parts"})
  @DisplayName("Bearer 세션 토큰이 없거나 형식이 다르면 401로 거절한다")
  void rejectsMissingOrMalformedToken(String authorization) {
    final var count = BACKENDS.get("payment").count().get();

    client
        .get()
        .uri("/api/payments")
        .headers(
            headers -> {
              if (!authorization.isEmpty()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
              }
            })
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("COMMON_AUTHENTICATION_REQUIRED");

    then(verifier).shouldHaveNoInteractions();
    assertThat(BACKENDS.get("payment").count().get()).isEqualTo(count);
  }

  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("rejectedVerifications")
  @DisplayName("세션 판정에 따라 백엔드에 전달하지 않고 거절한다")
  void rejectsUnverifiedRequest(
      SessionVerification verification,
      int status,
      String code,
      String detail,
      CapturedOutput output) {
    given(verifier.verify(anyString())).willReturn(Mono.just(verification));
    final var count = BACKENDS.get("payment").count().get();

    client
        .get()
        .uri("/api/payments")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(code)
        .jsonPath("$.detail")
        .isEqualTo(detail);

    assertThat(BACKENDS.get("payment").count().get()).isEqualTo(count);
    assertThat(output.getAll()).doesNotContain(TOKEN, TOKEN_HASH);
  }

  static Stream<Arguments> rejectedVerifications() {
    return Stream.of(
        Arguments.of(
            SessionVerification.unauthenticated(),
            401,
            "COMMON_AUTHENTICATION_REQUIRED",
            "로그인이 필요합니다."),
        Arguments.of(
            SessionVerification.authenticated("SELLER", ACCOUNT_ID),
            403,
            "COMMON_ROLE_FORBIDDEN",
            "이 요청을 수행할 권한이 없습니다."),
        Arguments.of(
            SessionVerification.unavailable(),
            503,
            "COMMON_TEMPORARILY_UNAVAILABLE",
            "인증 상태를 확인할 수 없어 요청을 전달하지 않았습니다. 잠시 후 다시 시도해 주세요."));
  }

  static Stream<Arguments> externalOperations() {
    return operations(true);
  }

  static Stream<Arguments> internalOperations() {
    return operations(false);
  }

  private static Stream<Arguments> operations(boolean external) {
    return CONTRACT.required("paths").properties().stream()
        .filter(entry -> entry.getKey().startsWith("/api/") == external)
        .flatMap(
            entry ->
                entry.getValue().properties().stream()
                    .filter(operation -> operation.getValue().has("operationId"))
                    .map(
                        operation ->
                            external
                                ? Arguments.of(
                                    operation.getKey(),
                                    entry.getKey(),
                                    operation.getValue().required("x-owner-service").asText(),
                                    operation
                                        .getValue()
                                        .required("x-actor")
                                        .asText()
                                        .split("\\|")[0])
                                : Arguments.of(operation.getKey(), entry.getKey())));
  }

  private static String concretePath(String template) {
    return template.replaceAll("\\{[^/}]+}", "00000000-0000-4000-8000-000000000001");
  }

  private static JsonNode contract() {
    try (var input =
        GatewayRoutingIntegrationTest.class.getResourceAsStream("/contracts/openapi.json")) {
      return MAPPER.readTree(input);
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  private static Backend backend(String name) {
    final var last = new AtomicReference<CapturedRequest>();
    final var count = new AtomicInteger();
    final var server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) ->
                    request
                        .receive()
                        .aggregate()
                        .asString(StandardCharsets.UTF_8)
                        .defaultIfEmpty("")
                        .flatMap(
                            body -> {
                              final var uri = URI.create(request.uri());
                              count.incrementAndGet();
                              last.set(
                                  new CapturedRequest(
                                      request.method().name(),
                                      uri.getRawQuery(),
                                      request.requestHeaders().get("Idempotency-Key"),
                                      body,
                                      request.requestHeaders().copy()));
                              if ("mode=disconnect".equals(uri.getRawQuery())) {
                                return response
                                    .withConnection(connection -> connection.dispose())
                                    .then();
                              }
                              final var problem = "mode=problem".equals(uri.getRawQuery());
                              try {
                                final var payload =
                                    MAPPER.writeValueAsString(
                                        problem
                                            ? Map.of(
                                                "type",
                                                    "urn:commerce-settle:error:payment-probe-conflict",
                                                "title", "결제 충돌",
                                                "status", 409,
                                                "detail", "기존 결제 결과를 확인해 주세요.",
                                                "instance", "/api/payments",
                                                "code", "PAYMENT_PROBE_CONFLICT",
                                                "traceId", "backend-trace-probe")
                                            : Map.of("service", name, "path", uri.getRawPath()));
                                return response
                                    .status(problem ? 409 : 200)
                                    .header(
                                        "Content-Type",
                                        problem ? "application/problem+json" : "application/json")
                                    .sendString(Mono.just(payload))
                                    .then();
                              } catch (IOException error) {
                                return Mono.error(error);
                              }
                            }))
            .bindNow();
    return new Backend(server, last, count);
  }

  record CapturedRequest(
      String method,
      String query,
      String idempotencyKey,
      String body,
      io.netty.handler.codec.http.HttpHeaders headers) {}

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  record Backend(
      DisposableServer server, AtomicReference<CapturedRequest> last, AtomicInteger count) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class FailureRoutes {
    @Bean
    RouterFunction<ServerResponse> failureRoutes() {
      return RouterFunctions.route()
          .GET(
              "/gateway-test/failure",
              request -> Mono.error(new IllegalStateException("private-probe 127.0.0.1")))
          .GET(
              "/gateway-test/reset",
              request -> Mono.error(new IOException("Connection reset by peer private-probe")))
          .GET(
              "/gateway-test/abort",
              request -> Mono.error(reactor.netty.channel.AbortedException.beforeSend()))
          .GET(
              "/gateway-test/timeout",
              request ->
                  Mono.error(
                      new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "private-probe")))
          .build();
    }
  }
}
