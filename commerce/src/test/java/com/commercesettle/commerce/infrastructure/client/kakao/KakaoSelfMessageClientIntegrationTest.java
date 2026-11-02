package com.commercesettle.commerce.infrastructure.client.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerResponse;

class KakaoSelfMessageClientIntegrationTest {
  private static final long NOW = 1_793_606_400L;
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AtomicInteger sends = new AtomicInteger();
  private final AtomicInteger refreshes = new AtomicInteger();
  private final AtomicInteger identities = new AtomicInteger();
  private final AtomicInteger stalledResponses = new AtomicInteger();
  private final AtomicInteger cancelledStalls = new AtomicInteger();
  private DisposableServer server;
  private HttpClient http;
  private Path credentials;
  private KakaoSelfMessageClient client;
  private String sentText;
  private String sentAuthorization;
  private Map<String, String> refreshForm;
  private int sendStatus = 200;
  private int refreshStatus = 200;
  private int identityStatus = 200;
  private String sendBody = "{\"result_code\":0}";
  private String refreshBody = "{\"access_token\":\"renewed-access\",\"expires_in\":3600}";
  private String identityBody = "{\"id\":123,\"app_id\":456}";
  private boolean loseResponse;
  private boolean delayResponse;
  private String stallPath;

  @BeforeEach
  void setUp() throws IOException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    credentials = directory.resolve("credentials.json");
    writeCredentials(NOW + 3600);
    http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    server =
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
                              if (request.uri().equals("/oauth/token")) {
                                refreshes.incrementAndGet();
                                refreshForm = form(body);
                                return respond(request.uri(), response, refreshStatus, refreshBody);
                              }
                              if (request.uri().equals("/v1/user/access_token_info")) {
                                identities.incrementAndGet();
                                return respond(
                                    request.uri(), response, identityStatus, identityBody);
                              }
                              sends.incrementAndGet();
                              sentAuthorization = request.requestHeaders().get("Authorization");
                              try {
                                sentText =
                                    mapper
                                        .readTree(form(body).get("template_object"))
                                        .path("text")
                                        .asText();
                              } catch (IOException failure) {
                                return Mono.error(failure);
                              }
                              if (loseResponse) {
                                response.withConnection(connection -> connection.dispose());
                                return Mono.empty();
                              }
                              if (delayResponse) {
                                return Mono.delay(Duration.ofSeconds(6))
                                    .then(response.sendString(Mono.just(sendBody)).then());
                              }
                              return respond(request.uri(), response, sendStatus, sendBody);
                            }))
            .bindNow();
    client = clientAt(NOW);
  }

  private Mono<Void> respond(String path, HttpServerResponse response, int status, String body) {
    if (path.equals(stallPath)) {
      stalledResponses.incrementAndGet();
      response.withConnection(connection -> connection.onDispose(cancelledStalls::incrementAndGet));
      return response
          .status(status)
          .sendHeaders()
          .then()
          .then(response.sendString(Flux.concat(Mono.just("{"), Mono.never())).then());
    }
    return response.status(status).sendString(Mono.just(body)).then();
  }

  private KakaoSelfMessageClient clientAt(long epochSecond) {
    KakaoTokenManager tokens =
        new KakaoTokenManager(
            credentials,
            "123",
            "456",
            http,
            mapper,
            uri("/oauth/token"),
            uri("/v1/user/access_token_info"),
            Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC),
            registry);
    return new KakaoSelfMessageClient(
        http,
        mapper,
        tokens,
        uri("/v2/api/talk/memo/default/send"),
        URI.create("https://example.test/api/orders"));
  }

  @AfterEach
  void tearDown() {
    server.disposeNow();
    http.close();
    registry.close();
  }

  @Test
  @DisplayName("본인 계정 확인 뒤 실제 HTTP로 한글 거래 내용을 전송한다")
  void sendsEncodedTransactionAfterCheckingOwner() {
    String text = "[구매자] 주문 abc / 사과 2개 / 12,000원 / 발송 완료";

    assertThat(client.send(text)).isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);
    assertThat(client.send(text)).isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);

    assertThat(sentText).isEqualTo(text);
    assertThat(sentAuthorization).isEqualTo("Bearer original-access");
    assertThat(sends).hasValue(2);
    assertThat(identities).hasValue(1);
    assertThat(refreshes).hasValue(0);
  }

  @Test
  @DisplayName("갱신 응답에 리프레시 토큰이 없으면 기존 값을 보존한다")
  void keepsRefreshTokenWhenNotRotated() throws IOException {
    writeCredentials(NOW - 1);

    assertThat(client.send("[판매자] 판매 2건 / 합계 15,000원"))
        .isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);

    var saved = mapper.readTree(Files.readAllBytes(credentials));
    assertThat(saved.path("refreshToken").asText()).isEqualTo("original-refresh");
    assertThat(saved.path("expiresAt").asLong()).isEqualTo(NOW + 3600);
    assertThat(saved.path("refreshExpiresAt").asLong()).isEqualTo(NOW + 86400);
    assertThat(sentAuthorization).isEqualTo("Bearer renewed-access");
    assertThat(refreshForm)
        .containsEntry("grant_type", "refresh_token")
        .containsEntry("refresh_token", "original-refresh")
        .containsEntry("client_secret", "test-secret");
    assertThat(Files.getPosixFilePermissions(credentials))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  @DisplayName("회전된 리프레시 토큰과 만료 시각을 함께 저장한다")
  void savesRotatedRefreshToken() throws IOException {
    writeCredentials(NOW - 1);
    refreshBody =
        "{\"access_token\":\"renewed-access\",\"expires_in\":3600,"
            + "\"refresh_token\":\"next-refresh\",\"refresh_token_expires_in\":172800}";

    assertThat(client.send("[구매자] 환불 완료 / 7,000원"))
        .isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);

    var saved = mapper.readTree(Files.readAllBytes(credentials));
    assertThat(saved.path("refreshToken").asText()).isEqualTo("next-refresh");
    assertThat(saved.path("refreshExpiresAt").asLong()).isEqualTo(NOW + 172800);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 503})
  @DisplayName("소유자 조회의 일시 장애 뒤 재시작해도 회전된 자격을 복구한다")
  void recoversRotatedRefreshAfterTransientOwnerFailure(int status) throws IOException {
    writeCredentials(NOW - 1, NOW + 1);
    refreshBody =
        "{\"access_token\":\"renewed-access\",\"expires_in\":3600,"
            + "\"refresh_token\":\"next-refresh\",\"refresh_token_expires_in\":172800}";
    identityStatus = status;

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.RETRYABLE);

    var pending = mapper.readTree(Files.readAllBytes(credentials));
    assertThat(pending.path("accessToken").asText()).isEqualTo("original-access");
    assertThat(pending.path("pendingRefresh").path("refreshToken").asText())
        .isEqualTo("next-refresh");
    assertThat(sends).hasValue(0);
    assertThat(Files.getPosixFilePermissions(credentials))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    identityStatus = 200;
    registry.clear();
    client = clientAt(NOW + 2);

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);

    var saved = mapper.readTree(Files.readAllBytes(credentials));
    assertThat(saved.has("pendingRefresh")).isFalse();
    assertThat(saved.path("refreshToken").asText()).isEqualTo("next-refresh");
    assertThat(saved.path("refreshExpiresAt").asLong()).isEqualTo(NOW + 172800);
    assertThat(sentAuthorization).isEqualTo("Bearer renewed-access");
    assertThat(refreshes).hasValue(1);
  }

  @Test
  @DisplayName("보관한 access token도 만료됐으면 회전된 refresh token으로 복구한다")
  void refreshesExpiredPendingAccessWithRetainedRotation() throws IOException {
    writeCredentials(NOW - 1, NOW + 1);
    refreshBody =
        "{\"access_token\":\"renewed-access\",\"expires_in\":1,"
            + "\"refresh_token\":\"next-refresh\",\"refresh_token_expires_in\":172800}";
    identityStatus = 503;

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.RETRYABLE);

    identityStatus = 200;
    refreshBody = "{\"access_token\":\"recovered-access\",\"expires_in\":3600}";
    registry.clear();
    client = clientAt(NOW + 2);

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);

    assertThat(refreshForm).containsEntry("refresh_token", "next-refresh");
    assertThat(sentAuthorization).isEqualTo("Bearer recovered-access");
    var saved = mapper.readTree(Files.readAllBytes(credentials));
    assertThat(saved.has("pendingRefresh")).isFalse();
    assertThat(saved.path("refreshToken").asText()).isEqualTo("next-refresh");
    assertThat(saved.path("refreshExpiresAt").asLong()).isEqualTo(NOW + 172800);
  }

  @ParameterizedTest
  @CsvSource({
    "400, REJECTED",
    "403, REJECTED",
    "404, REJECTED",
    "408, UNKNOWN",
    "429, RETRYABLE",
    "503, UNKNOWN"
  })
  @DisplayName("확정 거절·재시도·발송 여부 미확정을 구분한다")
  void distinguishesDefiniteAndAmbiguousHttpResults(
      int status, KakaoSelfMessageClient.Result expected) {
    sendStatus = status;
    sendBody = "{\"code\":-2}";

    assertThat(client.send("[구매자] 배송 완료")).isEqualTo(expected);

    assertThat(sends).hasValue(1);
  }

  @Test
  @DisplayName("응답이 유실돼도 같은 호출 안에서 다시 발송하지 않는다")
  void doesNotResendAfterResponseLoss() {
    loseResponse = true;

    assertThat(client.send("[구매자] 결제 완료 / 9,000원"))
        .isEqualTo(KakaoSelfMessageClient.Result.UNKNOWN);

    assertThat(sends).hasValue(1);
  }

  @Test
  @DisplayName("발송 응답이 제한 시간 안에 오지 않으면 미확정으로 보존한다")
  void keepsSendTimeoutUnknown() {
    delayResponse = true;

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.UNKNOWN);

    assertThat(sends).hasValue(1);
  }

  @ParameterizedTest
  @CsvSource({
    "/oauth/token, RETRYABLE",
    "/v1/user/access_token_info, RETRYABLE",
    "/v2/api/talk/memo/default/send, UNKNOWN"
  })
  @Timeout(10)
  @DisplayName("헤더 뒤 본문이 멈춰도 제한 시간에 취소하고 다음 발송을 막지 않는다")
  void cancelsStalledBodyWithinSharedDeadline(String path, KakaoSelfMessageClient.Result result)
      throws IOException {
    stallPath = path;
    if (path.equals("/oauth/token")) writeCredentials(NOW - 1);
    long started = System.nanoTime();

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(result);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7));
    assertThat(stalledResponses).hasValue(1);
    assertThat(sends).hasValue(path.endsWith("/send") ? 1 : 0);
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(cancelledStalls).hasValue(1));
    stallPath = null;

    assertThat(client.send("[구매자] 별도 주문 발송 완료")).isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);
  }

  @Test
  @DisplayName("해석할 수 없는 성공 응답을 발송 성공으로 확정하지 않는다")
  void keepsMalformedSuccessUnknown() {
    sendBody = "{\"result_code\":\"0\"}";

    assertThat(client.send("[구매자] 발송 완료")).isEqualTo(KakaoSelfMessageClient.Result.UNKNOWN);
  }

  @Test
  @DisplayName("0으로 잘릴 수 있는 큰 결과 코드를 성공으로 인정하지 않는다")
  void keepsOverflowingResultUnknown() {
    sendBody = "{\"result_code\":4294967296}";

    assertThat(client.send("[구매자] 발송 완료")).isEqualTo(KakaoSelfMessageClient.Result.UNKNOWN);
  }

  @ParameterizedTest
  @CsvSource({
    "-1, RETRYABLE",
    "-7, RETRYABLE",
    "-10, RETRYABLE",
    "-603, UNKNOWN",
    "-402, REAUTHENTICATION_REQUIRED"
  })
  @DisplayName("카카오 오류 코드로 일시 장애·내부 타임아웃·추가 동의를 구분한다")
  void distinguishesKakaoErrorCodes(int code, KakaoSelfMessageClient.Result result) {
    sendStatus = code == -402 ? 403 : 400;
    sendBody = "{\"code\":" + code + "}";

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(result);

    assertThat(sends).hasValue(1);
    assertThat(
            registry.get("commerce.notification.kakao.reauthentication.required").gauge().value())
        .isEqualTo(code == -402 ? 1 : 0);
  }

  @Test
  @DisplayName("401은 다음 시도에서 갱신하며 즉시 중복 발송하지 않는다")
  void refreshesRejectedAccessOnNextAttempt() {
    sendStatus = 401;

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.RETRYABLE);
    assertThat(sends).hasValue(1);
    assertThat(refreshes).hasValue(0);
    sendStatus = 200;
    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.ACCEPTED);

    assertThat(refreshes).hasValue(1);
    assertThat(sentAuthorization).isEqualTo("Bearer renewed-access");
  }

  @ParameterizedTest
  @CsvSource({"400, REAUTHENTICATION_REQUIRED, 1", "503, RETRYABLE, 0", "429, RETRYABLE, 0"})
  @DisplayName("갱신 거절은 재인증으로, 일시 장애는 재시도로 안내한다")
  void separatesReauthenticationFromTransientRefresh(
      int status, KakaoSelfMessageClient.Result result, int gauge) throws IOException {
    writeCredentials(NOW - 1);
    refreshStatus = status;

    assertThat(client.send("[구매자] 결제 완료")).isEqualTo(result);

    assertThat(sends).hasValue(0);
    assertThat(
            registry.get("commerce.notification.kakao.reauthentication.required").gauge().value())
        .isEqualTo(gauge);
  }

  @Test
  @DisplayName("토큰의 실제 계정이 다르면 메시지 발송 전에 차단한다")
  void rejectsDifferentActualOwner() {
    identityBody = "{\"id\":999,\"app_id\":456}";

    assertThat(client.send("[구매자] 결제 완료"))
        .isEqualTo(KakaoSelfMessageClient.Result.REAUTHENTICATION_REQUIRED);

    assertThat(sends).hasValue(0);
    assertThat(refreshes).hasValue(0);
  }

  @Test
  @DisplayName("갱신된 토큰의 계정이 다르면 저장하거나 발송하지 않는다")
  void doesNotStoreRefreshForDifferentOwner() throws IOException {
    writeCredentials(NOW - 1);
    identityBody = "{\"id\":999,\"app_id\":456}";

    assertThat(client.send("[구매자] 결제 완료"))
        .isEqualTo(KakaoSelfMessageClient.Result.REAUTHENTICATION_REQUIRED);

    assertThat(sends).hasValue(0);
    assertThat(refreshes).hasValue(1);
    assertThat(mapper.readTree(Files.readAllBytes(credentials)).path("accessToken").asText())
        .isEqualTo("original-access");
  }

  @Test
  @DisplayName("다른 인증 작업이 잠금을 잡았으면 기다리며 발송하지 않는다")
  void retriesWithoutHttpWhenCredentialsAreLocked() throws IOException {
    Path lock = directory.resolve("credentials.json.lock");
    Files.createFile(
        lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      assertThat(client.send("[구매자] 결제 완료")).isEqualTo(KakaoSelfMessageClient.Result.RETRYABLE);

      assertThat(sends).hasValue(0);
      assertThat(identities).hasValue(0);
    }
  }

  @Test
  @DisplayName("토큰 파일 권한이 넓으면 읽거나 발송하지 않는다")
  void rejectsReadableTokenFile() throws IOException {
    Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-r--r--"));

    assertThat(client.send("[구매자] 결제 완료"))
        .isEqualTo(KakaoSelfMessageClient.Result.REAUTHENTICATION_REQUIRED);

    assertThat(sends).hasValue(0);
    assertThat(identities).hasValue(0);
  }

  @Test
  @DisplayName("200자를 넘는 메시지는 잘라 보내지 않고 사전에 거절한다")
  void rejectsOversizedMessageBeforeHttp() {
    assertThat(client.send("가".repeat(201))).isEqualTo(KakaoSelfMessageClient.Result.REJECTED);
    assertThat(sends).hasValue(0);
    assertThat(identities).hasValue(0);
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private void writeCredentials(long expiresAt) throws IOException {
    writeCredentials(expiresAt, NOW + 86400);
  }

  private void writeCredentials(long expiresAt, long refreshExpiresAt) throws IOException {
    var value = new LinkedHashMap<String, Object>();
    value.put("clientId", "test-client");
    value.put("clientSecret", "test-secret");
    value.put("kakaoUserId", "123");
    value.put("appId", "456");
    value.put("accessToken", "original-access");
    value.put("refreshToken", "original-refresh");
    value.put("expiresAt", expiresAt);
    value.put("refreshExpiresAt", refreshExpiresAt);
    Files.write(credentials, mapper.writeValueAsBytes(value));
    Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
  }

  private static Map<String, String> form(String body) {
    var values = new LinkedHashMap<String, String>();
    for (String pair : body.split("&")) {
      String[] item = pair.split("=", 2);
      values.put(
          URLDecoder.decode(item[0], StandardCharsets.UTF_8),
          URLDecoder.decode(item[1], StandardCharsets.UTF_8));
    }
    return values;
  }
}
