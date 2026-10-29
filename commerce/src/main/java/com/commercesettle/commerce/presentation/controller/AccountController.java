package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.AccountService;
import com.commercesettle.commerce.presentation.dto.AccountResponse;
import com.commercesettle.commerce.presentation.dto.SellerRegistrationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Accounts", description = "판매자 가입과 본인 계정 조회")
@RestController
public class AccountController {
  private static final URI CURRENT_ACCOUNT = URI.create("/api/accounts/me");

  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @Operation(
      operationId = "post_api_sellers",
      summary = "판매자 가입",
      description = "같은 이메일의 판매자 계정이 있으면 삭제한 계정이어도 409이다.")
  @PostMapping("/api/sellers")
  public ResponseEntity<AccountResponse> registerSeller(
      @Valid @RequestBody SellerRegistrationRequest request) {
    var account =
        accounts.registerSeller(
            request.email(), request.password(), request.displayName(), request.payoutAccountRef());
    return ResponseEntity.created(CURRENT_ACCOUNT).body(AccountResponse.from(account));
  }

  @Operation(operationId = "get_api_accounts_me", summary = "본인 계정")
  @GetMapping("/api/accounts/me")
  public AccountResponse current(@Parameter(hidden = true) AuthenticatedActor actor) {
    return AccountResponse.from(accounts.current(actor.kind(), actor.id()));
  }
}
