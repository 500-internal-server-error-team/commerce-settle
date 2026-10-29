package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.PasswordResetService;
import com.commercesettle.commerce.presentation.dto.AcceptedNoticeResponse;
import com.commercesettle.commerce.presentation.dto.RequestPasswordResetRequest;
import com.commercesettle.commerce.presentation.dto.ResetPasswordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Password reset", description = "구매자·판매자 비밀번호 복구")
@RestController
public class PasswordResetController {
  // 계정 존재 여부와 관계없이 같은 안내를 돌려준다.
  static final AcceptedNoticeResponse ACCEPTED =
      new AcceptedNoticeResponse("일치하는 등록 정보가 있으면 복구 방법을 안내합니다.");

  private final PasswordResetService passwordResets;

  public PasswordResetController(PasswordResetService passwordResets) {
    this.passwordResets = passwordResets;
  }

  @Operation(
      operationId = "post_api_password_reset_requests",
      summary = "재설정 요청",
      description = "계정 존재 여부와 관계없이 같은 200이다. 토큰은 응답에 넣지 않는다.")
  @PostMapping("/api/password-reset-requests")
  public AcceptedNoticeResponse request(@Valid @RequestBody RequestPasswordResetRequest request) {
    passwordResets.request(request.actorKind(), request.email());
    return ACCEPTED;
  }

  @Operation(
      operationId = "post_api_password_resets",
      summary = "비밀번호 재설정",
      description = "쓸 수 없는 토큰은 401이다. 503이면 비밀번호가 이미 바뀌었을 수 있으므로 다시 보내지 않는다.")
  @PostMapping("/api/password-resets")
  public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
    passwordResets.reset(request.actorKind(), request.token(), request.newPassword());
    return ResponseEntity.noContent().build();
  }
}
