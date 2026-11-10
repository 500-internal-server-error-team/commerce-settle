package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.MileageWalletView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MileageWallet", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record MileageWalletResponse(
    @Schema(minimum = "0") @JsonProperty(required = true) long balance,
    @Schema(minimum = "0") @JsonProperty(required = true) long reservedAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long availableAmount,
    @Schema(minimum = "0", description = "구매확정 전 적립 예정 합계. 잔액·가용액에 포함하지 않으며 사용할 수 없다.")
        @JsonProperty(required = true)
        long pendingAccrualAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long version) {

  public static MileageWalletResponse from(MileageWalletView view) {
    return new MileageWalletResponse(
        view.balance(),
        view.reservedAmount(),
        view.availableAmount(),
        view.pendingAccrualAmount(),
        view.version());
  }
}
