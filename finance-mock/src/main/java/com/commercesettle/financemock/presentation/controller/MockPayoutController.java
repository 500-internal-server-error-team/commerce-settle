package com.commercesettle.financemock.presentation.controller;

import com.commercesettle.financemock.application.dto.PayoutResult;
import com.commercesettle.financemock.application.service.MockPayoutLookupService;
import com.commercesettle.financemock.application.service.MockPayoutService;
import com.commercesettle.financemock.presentation.dto.MockPayoutRequest;
import com.commercesettle.financemock.presentation.dto.MockTransactionResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 지급 기관 대역. Settlement 지급 워커만 호출하며 Gateway에 공개하지 않는다. */
@RestController
@RequestMapping("/mock/payouts")
class MockPayoutController {
  private final MockPayoutService payouts;
  private final MockPayoutLookupService lookups;

  MockPayoutController(MockPayoutService payouts, MockPayoutLookupService lookups) {
    this.payouts = payouts;
    this.lookups = lookups;
  }

  /**
   * 지급을 실행한다. 202는 접수 응답이며 본문의 status로 결과를 구분한다. 응답 유실 시나리오면 결과를 저장한 뒤 본문 없는 504를 돌려준다. 멱등 헤더는 형식만
   * 검사하고, 중복·충돌은 본문의 업무 키로 판단해 헤더를 바꿔도 우회할 수 없다.
   */
  @PostMapping
  ResponseEntity<MockTransactionResponse> execute(
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody MockPayoutRequest request) {
    PayoutResult result =
        payouts.execute(
            request.businessKey(),
            request.accountRef(),
            request.sellerAccountRef(),
            request.amount(),
            request.scenarioVersion());
    if (result.responseLost()) {
      return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
    }
    return ResponseEntity.accepted()
        .location(URI.create("/mock/payouts/" + request.businessKey()))
        .body(MockTransactionResponse.from(result.view()));
  }

  @GetMapping("/{businessKey}")
  MockTransactionResponse find(
      @PathVariable UUID businessKey,
      @RequestParam @NotBlank @Size(max = 32) String scenarioVersion) {
    return MockTransactionResponse.from(lookups.find(businessKey, scenarioVersion));
  }
}
