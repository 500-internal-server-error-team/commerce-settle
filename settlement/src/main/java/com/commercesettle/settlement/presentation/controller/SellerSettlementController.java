package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.settlement.application.service.SellerSettlementQueryService;
import com.commercesettle.settlement.presentation.dto.PageResponses.PayoutPage;
import com.commercesettle.settlement.presentation.dto.PageResponses.SettlementBatchPage;
import com.commercesettle.settlement.presentation.dto.PageResponses.SettlementItemPage;
import com.commercesettle.settlement.presentation.dto.PayoutResponse;
import com.commercesettle.settlement.presentation.dto.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "settlement", description = "판매자 본인의 정산 항목·배치·지급을 조회한다.")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "형식·값 오류",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "401",
      description = "인증 필요/만료",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "403",
      description = "역할·서비스 권한 없음",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "404",
      description = "없거나 타인 소유",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "409",
      description = "상태·버전·기한·잔액·멱등 충돌",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "429",
      description = "요청 제한",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "503",
      description = "일시적 저장/외부 장애",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
@RequestMapping("/api/sellers/me")
public class SellerSettlementController {
  private final SellerSettlementQueryService queries;

  public SellerSettlementController(SellerSettlementQueryService queries) {
    this.queries = queries;
  }

  @Operation(operationId = "get_api_sellers_me_settlement_items", summary = "본인 정산 항목")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/settlement-items")
  public SettlementItemPage items(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return SettlementItemPage.from(queries.items(seller.id(), page, size));
  }

  @Operation(operationId = "get_api_sellers_me_settlement_batches", summary = "본인 정산 배치")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/settlement-batches")
  public SettlementBatchPage batches(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return SettlementBatchPage.from(queries.batches(seller.id(), page, size));
  }

  @Operation(operationId = "get_api_sellers_me_payouts", summary = "본인 지급 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/payouts")
  public PayoutPage payouts(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return PayoutPage.from(queries.payouts(seller.id(), page, size));
  }

  @Operation(operationId = "get_api_sellers_me_payouts_payoutId", summary = "본인 지급 조회")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/payouts/{payoutId}")
  public PayoutResponse payout(
      @Parameter(hidden = true) AuthenticatedSeller seller, @PathVariable UUID payoutId) {
    return PayoutResponse.from(queries.payout(seller.id(), payoutId));
  }
}
