package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.repository.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ProductPage", description = "createdAt DESC, id DESC 순서")
public record ProductPageResponse(
    List<ProductResponse> items, int page, int size, long totalElements, int totalPages) {
  public static ProductPageResponse from(PageResult<Product> result) {
    return new ProductPageResponse(
        result.items().stream().map(ProductResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
