package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
    name = "OrderPage",
    description = "createdAt DESC, id DESC 순서",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OrderPageResponse(
    @JsonProperty(required = true) List<OrderResponse> items,
    @Schema(minimum = "0") @JsonProperty(required = true) int page,
    @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
    @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
    @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
  public static OrderPageResponse from(PageResult<OrderView> result) {
    return new OrderPageResponse(
        result.items().stream().map(OrderResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
