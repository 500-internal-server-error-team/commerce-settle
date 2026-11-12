package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.SellerProfileService.SettlementContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ProductSettlementContext")
public record ProductSettlementContextResponse(
    UUID productId,
    UUID sellerId,
    String payoutAccountRef,
    long sellerVersion,
    @Schema(allowableValues = {"ACTIVE", "SUSPENDED", "DELETED"}) String sellerStatus) {

  public static ProductSettlementContextResponse from(SettlementContext context) {
    return new ProductSettlementContextResponse(
        context.productId(),
        context.sellerId(),
        context.payoutAccountRef(),
        context.sellerVersion(),
        context.sellerStatus().name());
  }
}
