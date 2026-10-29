package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.service.SessionService;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.presentation.dto.LoginRequest;
import com.commercesettle.commerce.presentation.dto.SessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sessions", description = "구매자·판매자 로그인과 현재 세션 로그아웃")
@RestController
public class SessionController {
  static final String SESSION_HASH = "X-Authenticated-Session-Hash";
  private static final Pattern TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");

  private final SessionService sessions;

  public SessionController(SessionService sessions) {
    this.sessions = sessions;
  }

  @Operation(
      operationId = "post_api_buyer_sessions",
      summary = "buyer 로그인",
      description = "자격 오류·정지/삭제 계정은 계정 존재 여부와 관계없이 같은 401이다.")
  @PostMapping("/api/buyer-sessions")
  public ResponseEntity<SessionResponse> buyerLogin(@Valid @RequestBody LoginRequest request) {
    return login(ActorKind.BUYER, request);
  }

  @Operation(
      operationId = "post_api_seller_sessions",
      summary = "seller 로그인",
      description = "자격 오류·정지/삭제 계정은 계정 존재 여부와 관계없이 같은 401이다.")
  @PostMapping("/api/seller-sessions")
  public ResponseEntity<SessionResponse> sellerLogin(@Valid @RequestBody LoginRequest request) {
    return login(ActorKind.SELLER, request);
  }

  @Operation(
      operationId = "delete_api_sessions_current",
      summary = "로그아웃",
      description = "Redis에서 현재 세션 삭제를 확인한 뒤 204를 반환한다. 이미 삭제된 세션도 204이다.")
  @DeleteMapping("/api/sessions/current")
  public ResponseEntity<Void> logout(
      @Parameter(hidden = true) AuthenticatedActor actor,
      @Parameter(hidden = true) @RequestHeader(name = SESSION_HASH, required = false)
          String sessionHash) {
    if (sessionHash == null || !TOKEN_HASH.matcher(sessionHash).matches()) {
      throw new AuthenticationFailedException();
    }
    sessions.logout(sessionHash, actor.kind(), actor.id());
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<SessionResponse> login(ActorKind kind, LoginRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(SessionResponse.from(sessions.login(kind, request.email(), request.password())));
  }
}
