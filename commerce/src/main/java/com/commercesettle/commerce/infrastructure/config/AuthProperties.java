package com.commercesettle.commerce.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 커머스 인증 설정. 값이 없거나 잘못되면 기동하지 않는다.
 *
 * @param sessionEpoch Gateway와 같은 인증 세대 UUID. 바꾸면 기존 세션이 모두 무효다.
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
    @NotNull UUID sessionEpoch,
    @NotNull @Valid RateLimit rateLimit,
    @NotNull @Valid PasswordReset passwordReset) {
  /**
   * @param identifierHmacKey 이메일을 제한 키로 바꾸는 서버 키. 모든 커머스 인스턴스가 같은 값을 쓴다.
   * @param loginMaxRequests 계정 종류·이메일별 시간창 안의 로그인 허용 횟수
   * @param loginWindowSeconds 로그인 제한의 고정 시간창
   * @param passwordResetMaxRequests 계정 종류·이메일별 시간창 안의 복구 요청 허용 횟수
   * @param passwordResetWindowSeconds 복구 요청 제한의 고정 시간창
   */
  public record RateLimit(
      @NotNull @Size(min = 32) String identifierHmacKey,
      @NotNull @Positive Integer loginMaxRequests,
      @NotNull @Positive Integer loginWindowSeconds,
      @NotNull @Positive Integer passwordResetMaxRequests,
      @NotNull @Positive Integer passwordResetWindowSeconds) {}

  /**
   * @param deliveryKey 복구 토큰 전달용 암호문의 서버 키. 전달 작업이 같은 값으로 복호화한다.
   */
  public record PasswordReset(@NotNull @Size(min = 32) String deliveryKey) {}
}
