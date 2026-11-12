package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.ProductCatalogService;
import com.commercesettle.commerce.presentation.dto.ProductPageResponse;
import com.commercesettle.commerce.presentation.dto.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Products", description = "구매자·비로그인 사용자의 판매 상품 조회")
@RestController
public class ProductController {
  private final ProductCatalogService catalog;

  public ProductController(ProductCatalogService catalog) {
    this.catalog = catalog;
  }

  @Operation(
      operationId = "get_api_products",
      summary = "판매 상품 목록",
      description = "ON_SALE이며 삭제되지 않은 상품만 반환한다.")
  @GetMapping("/api/products")
  public ProductPageResponse onSaleProducts(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ProductPageResponse.from(catalog.onSaleProducts(page, size));
  }

  @Operation(
      operationId = "get_api_products_productId",
      summary = "판매 상품 조회",
      description = "미공개·판매중단·삭제 상품은 404로 응답한다.")
  @GetMapping("/api/products/{productId}")
  public ProductResponse onSaleProduct(@PathVariable UUID productId) {
    return ProductResponse.from(catalog.onSaleProduct(productId));
  }
}
