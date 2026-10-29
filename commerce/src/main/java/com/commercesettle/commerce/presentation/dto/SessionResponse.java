package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.SessionService.Login;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Schema(name = "Session", description = "로그인 세션. token 원문은 이 응답에서만 반환하고 Redis에는 SHA-256 해시만 저장한다.")
public record SessionResponse(
    @Schema(description = "Authorization: Bearer로 보내는 불투명 토큰") String token,
    @Schema(allowableValues = "Bearer") String tokenType,
    @Schema(description = "발급 시각+30분. 요청으로 연장하지 않는다.") OffsetDateTime expiresAt,
    AccountResponse account) {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  public static SessionResponse from(Login login) {
    return new SessionResponse(
        login.token(),
        "Bearer",
        OffsetDateTime.ofInstant(login.expiresAt(), KST),
        AccountResponse.from(login.account()));
  }

  @Override
  public String toString() {
    return "SessionResponse[expiresAt=" + expiresAt + "]";
  }
}
