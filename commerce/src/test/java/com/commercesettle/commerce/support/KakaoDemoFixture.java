package com.commercesettle.commerce.support;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfMessageClient;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfNotificationGateway;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoTokenManager;
import com.commercesettle.commerce.infrastructure.config.KakaoSelfProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** 실제 HTTP를 사용하는 인증·발송 대역. 어떤 실제 카카오 자격도 사용하지 않는다. */
public final class KakaoDemoFixture implements AutoCloseable {
  private final List<String> messages = new CopyOnWriteArrayList<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DisposableServer server;
  private final KakaoSelfMessageClient client;
  private final KakaoSelfProperties properties;
  private final NotificationGateway gateway;
  private volatile boolean loseResponse;

  public KakaoDemoFixture(
      NotificationGateway mock,
      ObjectMapper mapper,
      Clock clock,
      Path directory,
      Set<UUID> buyers,
      Set<UUID> sellers)
      throws IOException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    Path file = directory.resolve("credentials.json");
    Files.createFile(
        file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(
        file,
        mapper.writeValueAsString(
            Map.of(
                "clientId",
                "test-client",
                "clientSecret",
                "test-secret",
                "kakaoUserId",
                "123",
                "appId",
                "456",
                "accessToken",
                "test-access",
                "refreshToken",
                "test-refresh",
                "expiresAt",
                clock.instant().getEpochSecond() + 3600,
                "refreshExpiresAt",
                clock.instant().getEpochSecond() + 120 * 86400)));
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
                              if (request.uri().equals("/info"))
                                return response
                                    .sendString(Mono.just("{\"id\":123,\"app_id\":456}"))
                                    .then();
                              if (request.uri().equals("/token"))
                                return response
                                    .sendString(
                                        Mono.just(
                                            "{\"access_token\":\"refreshed-access\",\"expires_in\":3600}"))
                                    .then();
                              try {
                                Map<String, String> fields =
                                    Arrays.stream(body.split("&"))
                                        .map(field -> field.split("=", 2))
                                        .collect(
                                            Collectors.toMap(
                                                field ->
                                                    URLDecoder.decode(
                                                        field[0], StandardCharsets.UTF_8),
                                                field ->
                                                    URLDecoder.decode(
                                                        field[1], StandardCharsets.UTF_8)));
                                messages.add(
                                    mapper
                                        .readTree(fields.get("template_object"))
                                        .path("text")
                                        .asText());
                              } catch (IOException | RuntimeException failure) {
                                return Mono.error(failure);
                              }
                              if (loseResponse) {
                                response.withConnection(connection -> connection.dispose());
                                return Mono.empty();
                              }
                              return response.sendString(Mono.just("{\"result_code\":0}")).then();
                            }))
            .bindNow();
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    var tokens =
        new KakaoTokenManager(
            file, "123", "456", http, mapper, uri("/token"), uri("/info"), clock, registry);
    client =
        new KakaoSelfMessageClient(
            http, mapper, tokens, uri("/send"), URI.create("https://example.test/orders"));
    properties =
        new KakaoSelfProperties(
            file, "123", "456", URI.create("https://example.test/orders"), buyers, sellers);
    gateway = new KakaoSelfNotificationGateway(mock, client, properties, clock);
  }

  public NotificationGateway gateway() {
    return gateway;
  }

  public KakaoSelfProperties properties() {
    return properties;
  }

  public KakaoSelfMessageClient client() {
    return client;
  }

  public List<String> messages() {
    return List.copyOf(messages);
  }

  public void loseResponse(boolean value) {
    loseResponse = value;
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  @Override
  public void close() {
    client.close();
    server.disposeNow();
    registry.close();
  }
}
