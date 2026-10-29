package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.service.OrderStubService;
import com.commercesettle.commerce.presentation.dto.CreateOrderRequest;
import com.commercesettle.commerce.presentation.dto.OrderPageResponse;
import com.commercesettle.commerce.presentation.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RestController
@RequestMapping("/api/orders")
@ConditionalOnProperty(name = "app.order-stub.enabled", havingValue = "true")
public class OrderStubController {
  private final OrderStubService orders;

  public OrderStubController(OrderStubService orders) {
    this.orders = orders;
  }

  @Operation(operationId = "post_api_orders", summary = "주문 생성 stub")
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
  @GetMapping
  public OrderPageResponse list(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return OrderPageResponse.from(orders.list(buyer.id(), page, size));
  }

  @Operation(operationId = "get_api_orders_orderId", summary = "본인 주문 조회 stub")
  @GetMapping("/{orderId}")
  public OrderResponse get(
      @Parameter(hidden = true) AuthenticatedBuyer buyer, @PathVariable UUID orderId) {
    return OrderResponse.from(orders.get(buyer.id(), orderId));
  }
}
