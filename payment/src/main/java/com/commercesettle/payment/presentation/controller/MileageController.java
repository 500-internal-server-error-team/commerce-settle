package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.service.MileageQueryService;
import com.commercesettle.payment.presentation.dto.MileageEntryPageResponse;
import com.commercesettle.payment.presentation.dto.MileageWalletResponse;
import com.commercesettle.payment.presentation.dto.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mileage", description = "구매자 본인의 마일리지 잔액과 이력을 조회한다.")
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
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
@RequestMapping("/api/buyers/me/mileage")
public class MileageController {
  private final MileageQueryService mileage;

  public MileageController(MileageQueryService mileage) {
    this.mileage = mileage;
  }

  @Operation(operationId = "get_api_buyers_me_mileage", summary = "본인 마일리지 잔액")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping
  public MileageWalletResponse wallet(@Parameter(hidden = true) AuthenticatedBuyer buyer) {
    return MileageWalletResponse.from(mileage.wallet(buyer.id()));
  }

  @Operation(operationId = "get_api_buyers_me_mileage_entries", summary = "본인 마일리지 이력")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/entries")
  public MileageEntryPageResponse entries(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return MileageEntryPageResponse.from(mileage.entries(buyer.id(), page, size));
  }
}
