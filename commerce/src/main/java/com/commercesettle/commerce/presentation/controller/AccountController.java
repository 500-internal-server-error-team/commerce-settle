package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.AccountService;
import com.commercesettle.commerce.presentation.dto.AccountResponse;
import com.commercesettle.commerce.presentation.dto.BuyerRegistrationRequest;
import com.commercesettle.commerce.presentation.dto.SellerRegistrationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Accounts", description = "구매자·판매자 가입과 본인 계정 조회")
@RestController
public class AccountController {
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @Operation(
      operationId = "post_api_buyers",
      summary = "구매자 가입",
      description =
          "가입과 웰컴 쿠폰 한 장을 함께 저장한다. 24시간 안에 같은 키·본문으로 재요청하면 같은 계정을 201로 반환하고 쿠폰을 더 발급하지 않는다. 같은 키의"
              + " 다른 본문·처리 중인 같은 키·이미 가입한 이메일은 409이다.")
  @PostMapping("/api/buyers")
  public ResponseEntity<AccountResponse> registerBuyer(
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody BuyerRegistrationRequest request) {
    var account =
        accounts.registerBuyer(
            idempotencyKey, request.email(), request.password(), request.displayName());
    return ResponseEntity.created(URI.create("/api/buyers/" + account.getId()))
        .body(AccountResponse.from(account));
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
    return ResponseEntity.created(URI.create("/api/sellers/" + account.getId()))
        .body(AccountResponse.from(account));
  }

  @Operation(operationId = "get_api_accounts_me", summary = "본인 계정")
  @GetMapping("/api/accounts/me")
  public AccountResponse current(@Parameter(hidden = true) AuthenticatedActor actor) {
    return AccountResponse.from(accounts.current(actor.kind(), actor.id()));
  }
}
