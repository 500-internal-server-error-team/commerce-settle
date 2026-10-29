package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "SellerRegistration", description = "판매자 가입. 이메일은 앞뒤 공백을 지우고 소문자로 맞춰 저장한다.")
public record SellerRegistrationRequest(
    @NotNull @Size(min = 1, max = 254) @Email String email,
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotNull @Size(min = 12, max = 128)
        String password,
    @NotNull @Size(min = 1, max = 80) String displayName,
    @Schema(description = "시험용 지급 계정 참조") @NotNull @Size(min = 1, max = 100)
        String payoutAccountRef) {

  // 형식 검증이 앞뒤 공백 때문에 거절하지 않도록 검증 전에 지운다.
  public SellerRegistrationRequest {
    email = email == null ? null : email.strip();
  }

  // 로그·예외 메시지에 비밀번호가 실리지 않게 한다.
  @Override
  public String toString() {
    return "SellerRegistrationRequest[displayName=" + displayName + "]";
  }
}
