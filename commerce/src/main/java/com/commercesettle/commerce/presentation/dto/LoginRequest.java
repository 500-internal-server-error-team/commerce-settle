package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "LoginRequest")
public record LoginRequest(
    @NotNull @Size(min = 1, max = 254) @Email String email,
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotNull @Size(min = 12, max = 128)
        String password) {

  // 로그·예외 메시지에 이메일과 비밀번호가 실리지 않게 한다.
  @Override
  public String toString() {
    return "LoginRequest[]";
  }
}
