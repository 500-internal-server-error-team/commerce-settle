package com.commercesettle.commerce.infrastructure.client.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 시연자 한 명의 자격을 보관한다. 토큰·원응답은 예외 메시지나 로그로 전달하지 않는다. */
public final class KakaoTokenManager {
  private final Path file;
  private final String userId;
  private final String appId;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final URI tokenUri;
  private final URI infoUri;
  private final Clock clock;
  private final AtomicBoolean reauthenticationRequired = new AtomicBoolean();
  private String rejectedAccessToken;
  private String verifiedAccessToken;

  public KakaoTokenManager(
      Path file,
      String userId,
      String appId,
      HttpClient http,
      ObjectMapper mapper,
      URI tokenUri,
      URI infoUri,
      Clock clock,
      MeterRegistry registry) {
    this.file = file.toAbsolutePath().normalize();
    this.userId = userId;
    this.appId = appId;
    this.http = http;
    this.mapper = mapper;
    this.tokenUri = tokenUri;
    this.infoUri = infoUri;
    this.clock = clock;
    Gauge.builder(
            "commerce.notification.kakao.reauthentication.required",
            reauthenticationRequired,
            value -> value.get() ? 1 : 0)
        .register(registry);
  }

  synchronized String accessToken(long deadline) {
    try {
      secure(file.getParent(), true);
      Path lock = file.resolveSibling(file.getFileName() + ".lock");
      if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
        try {
          Files.createFile(
              lock,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
          // 다른 시연 프로세스가 먼저 만든 잠금도 아래에서 권한과 링크를 검사한다.
        }
      }
      secure(lock, false);
      try (FileChannel channel =
              FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
          var held = channel.tryLock()) {
        if (held == null) throw new TokenFailure(false);
        secure(file, false);
        if (Files.size(file) > 32768) throw new TokenFailure(true);
        JsonNode stored = mapper.readTree(Files.readAllBytes(file));
        if (!(stored instanceof ObjectNode credentials)) throw new TokenFailure(true);
        if (!userId.equals(text(credentials, "kakaoUserId"))
            || !appId.equals(text(credentials, "appId"))) {
          throw new TokenFailure(true);
        }
        String access = text(credentials, "accessToken");
        long now = clock.instant().getEpochSecond();
        if (credentials.path("expiresAt").asLong(0) > now + 60
            && !access.equals(rejectedAccessToken)) {
          if (verify(access, deadline)) {
            reauthenticationRequired.set(false);
            return access;
          }
          rejectedAccessToken = access;
        }
        if (credentials.path("refreshExpiresAt").asLong(0) <= now) throw new TokenFailure(true);
        String body =
            form(
                Map.of(
                    "grant_type",
                    "refresh_token",
                    "client_id",
                    text(credentials, "clientId"),
                    "client_secret",
                    text(credentials, "clientSecret"),
                    "refresh_token",
                    text(credentials, "refreshToken")));
        HttpResponse<String> response =
            http.send(
                HttpRequest.newBuilder(tokenUri)
                    .timeout(remaining(deadline))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429 || response.statusCode() >= 500)
          throw new TokenFailure(false);
        if (response.statusCode() != 200) throw new TokenFailure(true);
        JsonNode renewed = mapper.readTree(response.body());
        String next = text(renewed, "access_token");
        if (renewed.path("expires_in").asLong(0) <= 0) throw new TokenFailure(true);
        if (!verify(next, deadline)) throw new TokenFailure(true);
        credentials.put("accessToken", next);
        credentials.put("expiresAt", Math.addExact(now, renewed.path("expires_in").asLong()));
        if (renewed.hasNonNull("refresh_token")) {
          credentials.put("refreshToken", text(renewed, "refresh_token"));
          if (renewed.path("refresh_token_expires_in").asLong(0) <= 0) throw new TokenFailure(true);
          credentials.put(
              "refreshExpiresAt",
              Math.addExact(now, renewed.path("refresh_token_expires_in").asLong()));
        }
        save(credentials);
        rejectedAccessToken = null;
        reauthenticationRequired.set(false);
        return next;
      }
    } catch (TokenFailure failure) {
      if (failure.reauthenticate) reauthenticationRequired.set(true);
      throw failure;
    } catch (OverlappingFileLockException busy) {
      throw new TokenFailure(false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new TokenFailure(false);
    } catch (java.nio.file.FileSystemException
        | com.fasterxml.jackson.core.JsonProcessingException
        | IllegalArgumentException
        | ArithmeticException failure) {
      reauthenticationRequired.set(true);
      throw new TokenFailure(true);
    } catch (IOException failure) {
      throw new TokenFailure(false);
    }
  }

  synchronized void rejected(String accessToken) {
    rejectedAccessToken = accessToken;
  }

  void requireReauthentication() {
    reauthenticationRequired.set(true);
  }

  private boolean verify(String access, long deadline) throws IOException, InterruptedException {
    if (access.equals(verifiedAccessToken)) return true;
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(infoUri)
                .timeout(remaining(deadline))
                .header("Authorization", "Bearer " + access)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 429 || response.statusCode() >= 500) throw new TokenFailure(false);
    if (response.statusCode() == 401) return false;
    if (response.statusCode() != 200) throw new TokenFailure(true);
    JsonNode info = mapper.readTree(response.body());
    if (info == null
        || !userId.equals(info.path("id").asText())
        || !appId.equals(info.path("app_id").asText())) {
      throw new TokenFailure(true);
    }
    verifiedAccessToken = access;
    return true;
  }

  private void save(ObjectNode credentials) throws IOException {
    Path temporary =
        Files.createTempFile(
            file.getParent(),
            ".kakao-token-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer bytes = ByteBuffer.wrap(mapper.writeValueAsBytes(credentials));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void secure(Path path, boolean directory) throws IOException {
    if (Files.isSymbolicLink(path)
        || (directory ? !Files.isDirectory(path) : !Files.isRegularFile(path))
        || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            .equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))) {
      throw new TokenFailure(true);
    }
  }

  static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String text(JsonNode value, String key) {
    JsonNode field = value.get(key);
    if (field == null
        || !field.isTextual()
        || field.asText().isBlank()
        || field.asText().chars().anyMatch(character -> character < 33 || character > 126)) {
      throw new TokenFailure(true);
    }
    return field.asText();
  }

  static Duration remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new TokenFailure(false);
    return Duration.ofNanos(nanos);
  }

  static final class TokenFailure extends RuntimeException {
    final boolean reauthenticate;

    TokenFailure(boolean reauthenticate) {
      super(
          reauthenticate
              ? "KAKAO_REAUTHENTICATION_REQUIRED"
              : "KAKAO_TOKEN_TEMPORARILY_UNAVAILABLE");
      this.reauthenticate = reauthenticate;
    }
  }
}
