package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.SellerProfileService;
import com.commercesettle.commerce.presentation.dto.ProductSettlementContextResponse;
import com.commercesettle.commerce.presentation.dto.SellerProfileResponse;
import com.commercesettle.commerce.presentation.dto.SellerProfileUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller profile", description = "판매자 본인 프로필과 Settlement용 지급 컨텍스트")
@RestController
public class SellerProfileController {
  private final SellerProfileService sellers;

  public SellerProfileController(SellerProfileService sellers) {
    this.sellers = sellers;
  }

  @Operation(operationId = "get_api_sellers_me", summary = "본인 판매자 정보")
  @GetMapping("/api/sellers/me")
  public SellerProfileResponse profile(@Parameter(hidden = true) AuthenticatedSeller seller) {
    return SellerProfileResponse.from(sellers.profile(seller.id()));
  }

  @Operation(
      operationId = "put_api_sellers_me",
      summary = "판매자 정보 변경",
      description = "표시명·시험 수취 계정만 바꾼다. expectedVersion이 다르면 409이다. 이미 만든 지급의 계정 사본은 바뀌지 않는다.")
  @PutMapping("/api/sellers/me")
  public SellerProfileResponse change(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody SellerProfileUpdateRequest request) {
    return SellerProfileResponse.from(
        sellers.change(
            seller.id(),
            idempotencyKey,
            request.displayName(),
            request.payoutAccountRef(),
            request.expectedVersion()));
  }

  @Operation(
      operationId = "get_internal_products_productId_settlement_context",
      summary = "상품·판매자 지급 컨텍스트",
      description = "Settlement 서비스 자격만 호출한다. 논리삭제한 상품도 조회하며 판매자 상태를 그대로 돌려준다.")
  @GetMapping("/internal/products/{productId}/settlement-context")
  public ProductSettlementContextResponse settlementContext(@PathVariable UUID productId) {
    return ProductSettlementContextResponse.from(sellers.settlementContext(productId));
  }
}
