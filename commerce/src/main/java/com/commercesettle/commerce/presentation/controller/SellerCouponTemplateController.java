package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.SellerCouponService;
import com.commercesettle.commerce.application.service.SellerCouponService.Terms;
import com.commercesettle.commerce.domain.exception.InvalidCouponTermsException;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.presentation.dto.BuyerCouponResponse;
import com.commercesettle.commerce.presentation.dto.CouponTemplatePageResponse;
import com.commercesettle.commerce.presentation.dto.CouponTemplateResponse;
import com.commercesettle.commerce.presentation.dto.CreateCouponTemplateRequest;
import com.commercesettle.commerce.presentation.dto.IssueCouponRequest;
import com.commercesettle.commerce.presentation.dto.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

@Tag(name = "Seller coupons", description = "판매자 본인 쿠폰 캠페인과 발급")
@RestController
@RequestMapping("/api/sellers/me/coupon-templates")
public class SellerCouponTemplateController {
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final SellerCouponService coupons;

  public SellerCouponTemplateController(SellerCouponService coupons) {
    this.coupons = coupons;
  }

  @Operation(
      operationId = "post_api_sellers_me_coupon_templates",
      summary = "쿠폰 템플릿 등록",
      description =
          "서버가 kind=SELLER, funder=SELLER와 인증된 sellerId를 정한다. 상품 소유·할인 후 최소 20,000원·최소 정산액·예산·손익 하한을 검증한다.")
  @PostMapping
  public ResponseEntity<CouponTemplateResponse> register(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody CreateCouponTemplateRequest request) {
    CouponTemplate template =
        coupons.register(
            seller.id(),
            idempotencyKey,
            new Terms(
                request.productId(),
                request.name(),
                request.discountAmount(),
                request.minOrderAmount(),
                request.issuanceLimit(),
                request.validFrom().toInstant(),
                request.validUntil().toInstant(),
                request.totalBudget(),
                request.minSellerNetAmount(),
                distinctMethods(request.allowedMethods())));
    return ResponseEntity.created(
            URI.create("/api/sellers/me/coupon-templates/" + template.getId()))
        .body(CouponTemplateResponse.from(template));
  }

  @Operation(operationId = "get_api_sellers_me_coupon_templates", summary = "쿠폰 템플릿 목록")
  @GetMapping
  public CouponTemplatePageResponse campaigns(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return CouponTemplatePageResponse.from(coupons.campaigns(seller.id(), page, size));
  }

  @Operation(
      operationId = "get_api_sellers_me_coupon_templates_templateId",
      summary = "본인 쿠폰 템플릿 조회")
  @GetMapping("/{templateId}")
  public CouponTemplateResponse campaign(
      @Parameter(hidden = true) AuthenticatedSeller seller, @PathVariable UUID templateId) {
    return CouponTemplateResponse.from(coupons.campaign(seller.id(), templateId));
  }

  @Operation(
      operationId = "post_api_sellers_me_coupon_templates_templateId_closure",
      summary = "쿠폰 신규 발급 종료",
      description = "이미 발급한 쿠폰 조건은 보존하고 새 발급만 막는다. expectedVersion이 다르면 409이다.")
  @PostMapping("/{templateId}/closure")
  public CouponTemplateResponse close(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID templateId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody VersionRequest request) {
    return CouponTemplateResponse.from(
        coupons.close(seller.id(), templateId, idempotencyKey, request.expectedVersion()));
  }

  @Operation(
      operationId = "post_api_sellers_me_coupon_templates_templateId_issues",
      summary = "구매자에게 판매자 쿠폰 발급",
      description =
          "활성 캠페인으로 등록된 구매자에게 한 장 발급한다. 같은 캠페인·구매자의 새 요청은 409, 발급 수·예산 소진은 409 COUPON_BUDGET_EXHAUSTED이다.")
  @PostMapping("/{templateId}/issues")
  public ResponseEntity<BuyerCouponResponse> issue(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID templateId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody IssueCouponRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            BuyerCouponResponse.from(
                coupons.issue(seller.id(), templateId, idempotencyKey, request.buyerId())));
  }

  /** 명세의 uniqueItems대로 같은 결제수단을 두 번 보내면 거절한다. null 원소는 요청 검증이 먼저 막는다. */
  private static Set<Order.ExternalMethod> distinctMethods(List<Order.ExternalMethod> methods) {
    EnumSet<Order.ExternalMethod> distinct = EnumSet.copyOf(methods);
    if (distinct.size() != methods.size()) {
      throw new InvalidCouponTermsException("allowedMethods", "같은 결제수단을 두 번 보낼 수 없습니다.");
    }
    return distinct;
  }
}
