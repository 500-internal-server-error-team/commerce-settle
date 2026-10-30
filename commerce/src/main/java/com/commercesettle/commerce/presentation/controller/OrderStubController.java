package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.service.OrderStubService;
import com.commercesettle.commerce.presentation.dto.CreateOrderRequest;
import com.commercesettle.commerce.presentation.dto.OrderPageResponse;
import com.commercesettle.commerce.presentation.dto.OrderResponse;
import com.commercesettle.commerce.presentation.dto.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Orders", description = "로컬 개발용 주문 stub. 실제 예약·결제를 수행하지 않는다.")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "입력 검증 실패",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "401",
      description = "인증 필요",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "403",
      description = "권한 부족",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "404",
      description = "대상 없음",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "409",
      description = "업무·멱등 충돌",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "429",
      description = "요청 제한 (구현 예정)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "default",
      description = "프레임워크·예상하지 못한 오류",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
@RequestMapping("/api/orders")
@ConditionalOnProperty(name = "app.order-stub.enabled", havingValue = "true")
public class OrderStubController {
  private final OrderStubService orders;

  public OrderStubController(OrderStubService orders) {
    this.orders = orders;
  }

  @Operation(operationId = "post_api_orders", summary = "주문 생성 stub")
  @ApiResponse(
      responseCode = "201",
      description = "주문 생성 또는 동일 멱등 요청 재응답",
      headers =
          @Header(
              name = "Location",
              required = true,
              description = "생성한 주문의 조회 경로",
              schema = @Schema(type = "string", format = "uri-reference")),
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = OrderResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "일시적 서비스 불가 (구현 예정)",
      headers =
          @Header(
              name = "Retry-After",
              description = "COMMON_CAPACITY_EXCEEDED일 때 재시도 전 대기 초. 다른 503에는 필수 아님.",
              schema = @Schema(type = "integer", minimum = "1")),
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
  @PostMapping
  public ResponseEntity<OrderResponse> create(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody CreateOrderRequest request) {
    OrderView order = orders.create(buyer.id(), key, request.toCommand());
    return ResponseEntity.created(URI.create("/api/orders/" + order.id()))
        .body(OrderResponse.from(order));
  }

  @Operation(operationId = "get_api_orders", summary = "본인 주문 목록 stub")
  @ApiResponse(
      responseCode = "200",
      description = "본인 주문 목록",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = OrderPageResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "일시적 서비스 불가 (구현 예정)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
  @GetMapping
  public OrderPageResponse list(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return OrderPageResponse.from(orders.list(buyer.id(), page, size));
  }

  @Operation(operationId = "get_api_orders_orderId", summary = "본인 주문 조회 stub")
  @ApiResponse(
      responseCode = "200",
      description = "본인 주문",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = OrderResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "일시적 서비스 불가 (구현 예정)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
  @GetMapping("/{orderId}")
  public OrderResponse get(
      @Parameter(hidden = true) AuthenticatedBuyer buyer, @PathVariable UUID orderId) {
    return OrderResponse.from(orders.get(buyer.id(), orderId));
  }
}
