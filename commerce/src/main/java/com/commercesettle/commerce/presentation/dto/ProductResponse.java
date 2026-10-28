package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Product")
public record ProductResponse(
    String name,
    long unitPrice,
    @Schema(allowableValues = {"DRAFT", "ON_SALE", "STOPPED"}) String status,
    UUID id,
    UUID sellerId,
    long version,
    Instant createdAt) {
  /** 판매자가 지정할 수 있는 상태. DELETED는 논리삭제로만 도달한다. */
  static final String SELLER_STATUSES = "DRAFT|ON_SALE|STOPPED";

  public static ProductResponse from(Product product) {
    return new ProductResponse(
        product.getName(),
        product.getUnitPrice(),
        product.getStatus().name(),
        product.getId(),
        product.getSellerId(),
        product.getVersion(),
        product.getCreatedAt());
  }
}
