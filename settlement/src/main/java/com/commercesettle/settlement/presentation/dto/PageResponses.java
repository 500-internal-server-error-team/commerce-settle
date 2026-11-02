package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.dto.PayoutView;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 판매자 목록 응답. 모두 createdAt DESC, id DESC 순서다. */
public final class PageResponses {
  private PageResponses() {}

  @Schema(
      name = "SettlementItemPage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record SettlementItemPage(
      @JsonProperty(required = true) List<SettlementItemResponse> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static SettlementItemPage from(PageView<SettlementItem> view) {
      return new SettlementItemPage(
          view.items().stream().map(SettlementItemResponse::from).toList(),
          view.page(),
          view.size(),
          view.totalElements(),
          view.totalPages());
    }
  }

  @Schema(
      name = "SettlementBatchPage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record SettlementBatchPage(
      @JsonProperty(required = true) List<SettlementBatchResponse> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static SettlementBatchPage from(PageView<SettlementBatch> view) {
      return new SettlementBatchPage(
          view.items().stream().map(SettlementBatchResponse::from).toList(),
          view.page(),
          view.size(),
          view.totalElements(),
          view.totalPages());
    }
  }

  @Schema(name = "PayoutPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record PayoutPage(
      @JsonProperty(required = true) List<PayoutResponse> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static PayoutPage from(PageView<PayoutView> view) {
      return new PayoutPage(
          view.items().stream().map(PayoutResponse::from).toList(),
          view.page(),
          view.size(),
          view.totalElements(),
          view.totalPages());
    }
  }
}
