package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.repository.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "EventPage", description = "createdAt DESC, id DESC 순서")
public record EventPageResponse(
    List<EventResponse> items, int page, int size, long totalElements, int totalPages) {
  public static EventPageResponse from(PageResult<SaleEvent> result) {
    return new EventPageResponse(
        result.items().stream().map(EventResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
