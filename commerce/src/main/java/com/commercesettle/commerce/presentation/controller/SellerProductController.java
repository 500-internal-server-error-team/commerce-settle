package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.ProductCatalogService;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.presentation.dto.AdjustInventoryRequest;
import com.commercesettle.commerce.presentation.dto.CreateProductRequest;
import com.commercesettle.commerce.presentation.dto.InventoryResponse;
import com.commercesettle.commerce.presentation.dto.ProductPageResponse;
import com.commercesettle.commerce.presentation.dto.ProductResponse;
import com.commercesettle.commerce.presentation.dto.UpdateProductRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller products", description = "판매자 본인 상품과 재고 관리")
@RestController
@RequestMapping("/api/sellers/me/products")
public class SellerProductController {
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final ProductCatalogService catalog;

  public SellerProductController(ProductCatalogService catalog) {
    this.catalog = catalog;
  }

  @Operation(
      operationId = "post_api_sellers_me_products",
      summary = "상품 등록",
      description = "재고 0인 재고 행을 함께 만든다. 같은 키·본문의 재요청은 기존 상품을 반환한다.")
  @PostMapping
  public ResponseEntity<ProductResponse> register(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody CreateProductRequest request) {
    Product product =
        catalog.register(
            seller.id(),
            idempotencyKey,
            request.name(),
            request.unitPrice(),
            ProductStatus.valueOf(request.status()));
    return ResponseEntity.created(URI.create("/api/sellers/me/products/" + product.getId()))
        .body(ProductResponse.from(product));
  }

  @Operation(operationId = "get_api_sellers_me_products", summary = "본인 상품 목록")
  @GetMapping
  public ProductPageResponse sellerProducts(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ProductPageResponse.from(catalog.sellerProducts(seller.id(), page, size));
  }

  @Operation(operationId = "get_api_sellers_me_products_productId", summary = "본인 상품 조회")
  @GetMapping("/{productId}")
  public ProductResponse sellerProduct(
      @Parameter(hidden = true) AuthenticatedSeller seller, @PathVariable UUID productId) {
    return ProductResponse.from(catalog.sellerProduct(seller.id(), productId));
  }

  @Operation(
      operationId = "put_api_sellers_me_products_productId",
      summary = "상품 변경",
      description = "expectedVersion이 현재 버전과 다르면 409이다.")
  @PutMapping("/{productId}")
  public ProductResponse change(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID productId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody UpdateProductRequest request) {
    return ProductResponse.from(
        catalog.change(
            seller.id(),
            productId,
            idempotencyKey,
            request.name(),
            request.unitPrice(),
            ProductStatus.valueOf(request.status()),
            request.expectedVersion()));
  }

  @Operation(
      operationId = "delete_api_sellers_me_products_productId",
      summary = "상품 논리삭제",
      description = "새 판매를 중단한다. 주문 사본·발송·환불 이력은 유지한다.")
  @DeleteMapping("/{productId}")
  public ResponseEntity<Void> delete(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID productId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @RequestParam @Min(0) long expectedVersion) {
    catalog.delete(seller.id(), productId, idempotencyKey, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "get_api_sellers_me_products_productId_inventory", summary = "본인 상품 재고")
  @GetMapping("/{productId}/inventory")
  public InventoryResponse inventory(
      @Parameter(hidden = true) AuthenticatedSeller seller, @PathVariable UUID productId) {
    return InventoryResponse.from(catalog.inventory(seller.id(), productId));
  }

  @Operation(
      operationId = "post_api_sellers_me_products_productId_inventory_adjustments",
      summary = "일반 가용 재고 조정",
      description = "가용 수량만 바꾸며 예약·이벤트 배정·판매 수량은 바꾸지 않는다. 가용 수량이 음수가 되면 409이다.")
  @PostMapping("/{productId}/inventory-adjustments")
  public InventoryResponse adjustInventory(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID productId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody AdjustInventoryRequest request) {
    return InventoryResponse.from(
        catalog.adjustAvailable(
            seller.id(),
            productId,
            idempotencyKey,
            request.delta(),
            request.expectedVersion(),
            request.reason()));
  }
}
