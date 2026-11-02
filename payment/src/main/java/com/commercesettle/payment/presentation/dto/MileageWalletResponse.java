package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.MileageWalletView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MileageWallet", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record MileageWalletResponse(
    @Schema(minimum = "0") @JsonProperty(required = true) long balance,
    @Schema(minimum = "0") @JsonProperty(required = true) long reservedAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long availableAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long version) {

  public static MileageWalletResponse from(MileageWalletView view) {
    return new MileageWalletResponse(
        view.balance(), view.reservedAmount(), view.availableAmount(), view.version());
  }
}
