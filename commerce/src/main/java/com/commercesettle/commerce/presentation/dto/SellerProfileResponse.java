package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.SellerAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "SellerProfile")
public record SellerProfileResponse(
    UUID id,
    @Schema(allowableValues = {"BUYER", "SELLER"}) String actorKind,
    String displayName,
    @Schema(allowableValues = {"ACTIVE", "SUSPENDED", "DELETED"}) String status,
    long version,
    @Schema(description = "시험용 지급 계정 참조") String payoutAccountRef) {

  public static SellerProfileResponse from(SellerAccount seller) {
    return new SellerProfileResponse(
        seller.getId(),
        seller.actorKind().name(),
        seller.getDisplayName(),
        seller.getStatus().name(),
        seller.getVersion(),
        seller.getPayoutAccountRef());
  }
}
