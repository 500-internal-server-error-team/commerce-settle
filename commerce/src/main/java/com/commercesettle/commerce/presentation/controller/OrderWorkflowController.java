package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.OrderRefundService;
import com.commercesettle.commerce.application.service.OrderWorkflowService;
import com.commercesettle.commerce.presentation.dto.OrderResponse;
import com.commercesettle.commerce.presentation.dto.OrderWorkflowRequests;
import com.commercesettle.commerce.presentation.dto.OrderWorkflowResponses;
import com.commercesettle.commerce.presentation.dto.ProblemResponse;
import com.commercesettle.internalauth.InternalCallerFilter;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
      description = "상태·버전·기한·멱등 충돌",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "503",
      description = "저장소 장애·소유 어댑터 미연결",
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
@Tag(name = "Order workflow", description = "주문·배송·취소/반품 처리. 금융 성공은 Payment 결과로만 반영한다.")
@ConditionalOnProperty(
    name = "app.order-stub.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class OrderWorkflowController {
  private final OrderWorkflowService orders;
  private final OrderRefundService refunds;

  public OrderWorkflowController(OrderWorkflowService orders, OrderRefundService refunds) {
    this.orders = orders;
    this.refunds = refunds;
  }

  @ApiResponse(responseCode = "200", description = "조회·처리 완료", useReturnTypeSchema = true)
  @PostMapping("/api/order-quotes")
  @Operation(operationId = "post_api_order_quotes", summary = "예약 없는 주문 견적")
  public OrderWorkflowResponses.Quote quote(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @Valid @RequestBody OrderWorkflowRequests.Quote r) {
    return OrderWorkflowResponses.Quote.from(
        orders.quote(
            buyer.id(),
            r.productId(),
            r.quantity(),
            r.eventId(),
            r.buyerCouponId(),
            r.mileageAmount(),
            r.externalMethod()));
  }

  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  @GetMapping("/api/sellers/me/orders")
  @Operation(operationId = "get_api_sellers_me_orders", summary = "본인 판매 주문 목록")
  public OrderWorkflowResponses.Page<OrderWorkflowResponses.SellerOrder> sellerList(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return OrderWorkflowResponses.Page.from(
        orders.sellerOrders(seller.id(), page, size), OrderWorkflowResponses.SellerOrder::from);
  }

  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  @GetMapping("/api/sellers/me/orders/{orderId}")
  @Operation(operationId = "get_api_sellers_me_orders_orderId", summary = "이행 대상 주문과 배송지 사본")
  public OrderWorkflowResponses.SellerOrder sellerGet(
      @Parameter(hidden = true) AuthenticatedSeller seller, @PathVariable UUID orderId) {
    return OrderWorkflowResponses.SellerOrder.from(orders.sellerOrder(seller.id(), orderId));
  }

  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  @GetMapping("/internal/orders/{orderId}/payment-context")
  @Operation(
      operationId = "get_internal_orders_orderId_payment_context",
      summary = "Payment 전용 주문 컨텍스트")
  public OrderWorkflowResponses.PaymentContext context(@PathVariable UUID orderId) {
    return OrderWorkflowResponses.PaymentContext.from(orders.paymentContext(orderId));
  }

  @ApiResponse(
      responseCode = "202",
      useReturnTypeSchema = true,
      description = "비동기 접수; 금융 완료 아님",
      headers = @Header(name = "Location", required = true, description = "처리 결과 조회 경로"))
  @PostMapping("/api/orders/{orderId}/payment-retries")
  @Operation(operationId = "post_api_orders_orderId_payment_retries", summary = "확정 거절 뒤 재예약·결제 요청")
  public ResponseEntity<OrderResponse> retry(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Version r) {
    return ResponseEntity.accepted()
        .location(orderLocation(orderId))
        .body(OrderResponse.from(orders.retry(buyer.id(), orderId, key, r.expectedVersion())));
  }

  @ApiResponse(
      responseCode = "202",
      useReturnTypeSchema = true,
      description = "비동기 접수; 금융 완료 아님",
      headers = @Header(name = "Location", required = true, description = "처리 결과 조회 경로"))
  @PostMapping("/api/orders/{orderId}/cancellation")
  @Operation(
      operationId = "post_api_orders_orderId_cancellation",
      summary = "승인 차단을 기다리는 미결제 취소 접수")
  public ResponseEntity<OrderResponse> cancel(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Reason r) {
    return ResponseEntity.accepted()
        .location(orderLocation(orderId))
        .body(
            OrderResponse.from(
                orders.cancel(buyer.id(), orderId, key, r.expectedVersion(), r.reason())));
  }

  @ApiResponse(responseCode = "200", description = "조회·처리 완료", useReturnTypeSchema = true)
  @PostMapping("/api/sellers/me/orders/{orderId}/shipments")
  @Operation(
      operationId = "post_api_sellers_me_orders_orderId_shipments",
      summary = "최초 발송·모의 도착 시각 고정")
  public OrderResponse ship(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Shipment r) {
    return OrderResponse.from(
        orders.ship(seller.id(), orderId, key, r.expectedVersion(), r.shipmentReference()));
  }

  @ApiResponse(
      responseCode = "201",
      useReturnTypeSchema = true,
      description = "취소·반품 요청 생성",
      headers = @Header(name = "Location", required = true, description = "생성한 요청 조회 경로"))
  @PostMapping("/api/orders/{orderId}/refund-requests")
  @Operation(operationId = "post_api_orders_orderId_refund_requests", summary = "기한 내 취소·반품 접수")
  public ResponseEntity<OrderWorkflowResponses.Refund> createRefund(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.RefundCreate r) {
    var v = refunds.create(buyer.id(), orderId, key, r.expectedVersion(), r.kind(), r.reason());
    return ResponseEntity.created(refundLocation(v.id()))
        .body(OrderWorkflowResponses.Refund.from(v));
  }

  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  @GetMapping("/api/refund-requests/{requestId}")
  @Operation(operationId = "get_api_refund_requests_requestId", summary = "본인 취소·반품 진행 조회")
  public OrderWorkflowResponses.Refund getRefund(
      @Parameter(hidden = true) AuthenticatedBuyer buyer, @PathVariable UUID requestId) {
    return OrderWorkflowResponses.Refund.from(refunds.get(buyer.id(), requestId));
  }

  @ApiResponse(responseCode = "200", description = "조회·처리 완료", useReturnTypeSchema = true)
  @PostMapping("/api/refund-requests/{requestId}/withdrawal")
  @Operation(
      operationId = "post_api_refund_requests_requestId_withdrawal",
      summary = "판매자 승인 전 반품 철회")
  public OrderWorkflowResponses.Refund withdraw(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @PathVariable UUID requestId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Version r) {
    return OrderWorkflowResponses.Refund.from(
        refunds.withdraw(buyer.id(), requestId, key, r.expectedVersion()));
  }

  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  @GetMapping("/api/sellers/me/refund-requests")
  @Operation(operationId = "get_api_sellers_me_refund_requests", summary = "본인 판매 취소·반품 목록")
  public OrderWorkflowResponses.Page<OrderWorkflowResponses.Refund> sellerRefunds(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return OrderWorkflowResponses.Page.from(
        refunds.sellerList(seller.id(), page, size), OrderWorkflowResponses.Refund::from);
  }

  @ApiResponse(
      responseCode = "202",
      useReturnTypeSchema = true,
      description = "비동기 접수; 금융 완료 아님",
      headers = @Header(name = "Location", required = true, description = "처리 결과 조회 경로"))
  @PostMapping("/api/sellers/me/refund-requests/{requestId}/decision")
  @Operation(
      operationId = "post_api_sellers_me_refund_requests_requestId_decision",
      summary = "반품 승인·거절")
  public ResponseEntity<OrderWorkflowResponses.Refund> decide(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID requestId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Decision r) {
    return ResponseEntity.accepted()
        .location(refundLocation(requestId))
        .body(
            OrderWorkflowResponses.Refund.from(
                refunds.decide(
                    seller.id(),
                    requestId,
                    key,
                    r.expectedVersion(),
                    r.decision() == OrderWorkflowRequests.DecisionKind.APPROVE,
                    r.reason())));
  }

  @ApiResponse(responseCode = "200", description = "조회·처리 완료", useReturnTypeSchema = true)
  @PostMapping("/api/sellers/me/refund-requests/{requestId}/receipt")
  @Operation(
      operationId = "post_api_sellers_me_refund_requests_requestId_receipt",
      summary = "재판매 가능 반품 입고·재고 복원")
  public OrderWorkflowResponses.Refund receive(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID requestId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Version r) {
    return OrderWorkflowResponses.Refund.from(
        refunds.receive(seller.id(), requestId, key, r.expectedVersion()));
  }

  @ApiResponse(responseCode = "200", description = "조회·처리 완료", useReturnTypeSchema = true)
  @PostMapping("/internal/refund-requests/{requestId}/closure")
  @Operation(
      operationId = "post_internal_refund_requests_requestId_closure",
      summary = "확정 실패·미복원·예약 해제·고객확인 뒤 운영 종결")
  public OrderWorkflowResponses.Refund close(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID requestId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OrderWorkflowRequests.Closure r) {
    return OrderWorkflowResponses.Refund.from(
        refunds.close(
            operator,
            requestId,
            key,
            r.expectedVersion(),
            r.reason(),
            r.customerConfirmationRef()));
  }

  private static URI orderLocation(UUID id) {
    return URI.create("/api/orders/" + id);
  }

  private static URI refundLocation(UUID id) {
    return URI.create("/api/refund-requests/" + id);
  }
}
