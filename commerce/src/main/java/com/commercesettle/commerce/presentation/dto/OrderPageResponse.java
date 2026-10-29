package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.domain.repository.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "OrderPage", description = "createdAt DESC, id DESC 순서")
public record OrderPageResponse(
    List<OrderResponse> items, int page, int size, long totalElements, int totalPages) {
  public static OrderPageResponse from(PageResult<OrderView> result) {
    return new OrderPageResponse(
        result.items().stream().map(OrderResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
