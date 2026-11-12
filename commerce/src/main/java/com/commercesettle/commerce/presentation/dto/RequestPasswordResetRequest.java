package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.ActorKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "PasswordResetRequest")
public record RequestPasswordResetRequest(
    @NotNull ActorKind actorKind, @NotNull @Size(min = 1, max = 254) @Email String email) {

  // 로그·예외 메시지에 이메일이 실리지 않게 한다.
  @Override
  public String toString() {
    return "RequestPasswordResetRequest[actorKind=" + actorKind + "]";
  }
}
