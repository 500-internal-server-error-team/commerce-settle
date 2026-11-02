package com.commercesettle.commerce.infrastructure.client.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 실제 self-send에는 원수신 조회와 deliveryId 멱등 계약이 없다. HTTP 자동 재전송도 하지 않는다. */
public final class KakaoSelfMessageClient implements AutoCloseable {
  public enum Result {
    ACCEPTED,
    RETRYABLE,
    REJECTED,
    REAUTHENTICATION_REQUIRED,
    UNKNOWN
  }

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final KakaoTokenManager tokens;
  private final URI sendUri;
  private final URI link;

  public KakaoSelfMessageClient(
      HttpClient http, ObjectMapper mapper, KakaoTokenManager tokens, URI sendUri, URI link) {
    this.http = http;
    this.mapper = mapper;
    this.tokens = tokens;
    this.sendUri = sendUri;
    this.link = link;
  }

  public Result send(String text) {
    if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 200)
      return Result.REJECTED;
    long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    String access;
    try {
      access = tokens.accessToken(deadline);
    } catch (KakaoTokenManager.TokenFailure failure) {
      return failure.reauthenticate ? Result.REAUTHENTICATION_REQUIRED : Result.RETRYABLE;
    }
    try {
      String template =
          mapper.writeValueAsString(
              Map.of(
                  "object_type",
                  "text",
                  "text",
                  text,
                  "link",
                  Map.of("web_url", link.toString(), "mobile_web_url", link.toString()),
                  "button_title",
                  "시연 안내"));
      HttpRequest request =
          HttpRequest.newBuilder(sendUri)
              .timeout(KakaoTokenManager.remaining(deadline))
              .header("Authorization", "Bearer " + access)
              .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      KakaoTokenManager.form(Map.of("template_object", template))))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 401) {
        tokens.rejected(access);
        return Result.RETRYABLE;
      }
      if (response.statusCode() == 429) return Result.RETRYABLE;
      if (response.statusCode() == 400 || response.statusCode() == 403) {
        JsonNode error = mapper.readTree(response.body());
        if (error == null || !error.path("code").isInt()) return Result.UNKNOWN;
        int code = error.path("code").intValue();
        if (code == -603) return Result.UNKNOWN;
        if (code == -1 || code == -7 || code == -10) return Result.RETRYABLE;
        if (code == -402) {
          tokens.requireReauthentication();
          return Result.REAUTHENTICATION_REQUIRED;
        }
        return Result.REJECTED;
      }
      if (response.statusCode() == 404) return Result.REJECTED;
      if (response.statusCode() == 200) {
        JsonNode result = mapper.readTree(response.body());
        if (result != null
            && result.path("result_code").isInt()
            && result.path("result_code").intValue() == 0) return Result.ACCEPTED;
      }
      return Result.UNKNOWN;
    } catch (KakaoTokenManager.TokenFailure beforeSend) {
      return Result.RETRYABLE;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Result.UNKNOWN;
    } catch (IOException | RuntimeException failure) {
      // 전송을 시작한 뒤의 응답 유실은 성공 여부를 알 수 없다. 원본문·토큰은 기록하지 않는다.
      return Result.UNKNOWN;
    }
  }

  @Override
  public void close() {
    http.close();
  }
}
