package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.ActorKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "PasswordReset")
public record ResetPasswordRequest(
    @NotNull ActorKind actorKind,
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotNull @Size(min = 32, max = 512)
        String token,
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotNull @Size(min = 12, max = 128)
        String newPassword) {

  // 로그·예외 메시지에 복구 토큰과 비밀번호가 실리지 않게 한다.
  @Override
  public String toString() {
    return "ResetPasswordRequest[actorKind=" + actorKind + "]";
  }
}
