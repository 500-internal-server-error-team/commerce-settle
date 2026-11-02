package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.SettlementContextView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(
    name = "SettlementContext",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    description =
        "payment의 platformCouponAmount(P)·settlementBaseAmount(B)와 최초 feeAmount·sellerNetAmount를"
            + " 사용한다. E+M+P=B=feeAmount+sellerNetAmount를 대조한다.")
public record SettlementContextResponse(
    @JsonProperty(required = true) PaymentResponse payment,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long feeAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
        long sellerNetAmount,
    @JsonProperty(required = true) UUID sellerId,
    @JsonProperty(required = true) UUID productId,
    @Schema(minLength = 1, maxLength = 32) @JsonProperty(required = true) String policyVersion) {

  public static SettlementContextResponse from(SettlementContextView view) {
    return new SettlementContextResponse(
        PaymentResponse.from(view.payment()),
        view.feeAmount(),
        view.sellerNetAmount(),
        view.sellerId(),
        view.productId(),
        view.policyVersion());
  }
}
