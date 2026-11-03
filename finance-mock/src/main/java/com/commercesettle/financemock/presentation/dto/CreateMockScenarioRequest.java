package com.commercesettle.financemock.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.validation.Errors;

@Schema(name = "MockScenarioCreate", description = "대역·버전별 불변 설정. 확률은 소수점 7자리까지 저장한다.")
public record CreateMockScenarioRequest(
    @NotNull
        @Pattern(regexp = "PG_A|PG_B|PG_C|EASY_PAY|PAYOUT")
        @Schema(allowableValues = {"PG_A", "PG_B", "PG_C", "EASY_PAY", "PAYOUT"})
        String providerId,
    @NotBlank @Size(max = 32) String scenarioVersion,
    @NotNull @PositiveOrZero Integer minDelayMs,
    @NotNull @PositiveOrZero Integer maxDelayMs,
    @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 7)
        BigDecimal rejectionRate,
    @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 7)
        BigDecimal responseLossRate,
    @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 7)
        BigDecimal lookupFailureRate,
    @NotNull Long seed,
    @JsonProperty(required = true) @PositiveOrZero @Max(365) Integer settlementDelayBusinessDays,
    @JsonProperty(required = true) @PositiveOrZero Integer refundMinDelayMs,
    @JsonProperty(required = true) @PositiveOrZero Integer refundMaxDelayMs,
    @JsonProperty(required = true)
        @DecimalMin("0")
        @DecimalMax("1")
        @Digits(integer = 1, fraction = 7)
        BigDecimal refundRejectionRate,
    @JsonProperty(required = true)
        @DecimalMin("0")
        @DecimalMax("1")
        @Digits(integer = 1, fraction = 7)
        BigDecimal refundResponseLossRate,
    @NotNull @PositiveOrZero Integer lookupMinDelayMs,
    @NotNull @PositiveOrZero Integer lookupMaxDelayMs) {
  public CreateMockScenarioRequest {
    rejectionRate = normalize(rejectionRate);
    responseLossRate = normalize(responseLossRate);
    lookupFailureRate = normalize(lookupFailureRate);
    refundRejectionRate = normalize(refundRejectionRate);
    refundResponseLossRate = normalize(refundResponseLossRate);
  }

  private static BigDecimal normalize(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  public void validateRelations(Errors errors) {
    validateRange(errors, "maxDelayMs", minDelayMs, maxDelayMs);
    validateRange(errors, "refundMaxDelayMs", refundMinDelayMs, refundMaxDelayMs);
    validateRange(errors, "lookupMaxDelayMs", lookupMinDelayMs, lookupMaxDelayMs);
    if (providerId == null) return;
    boolean payout = "PAYOUT".equals(providerId);
    validatePaymentField(
        errors, payout, "settlementDelayBusinessDays", settlementDelayBusinessDays);
    validatePaymentField(errors, payout, "refundMinDelayMs", refundMinDelayMs);
    validatePaymentField(errors, payout, "refundMaxDelayMs", refundMaxDelayMs);
    validatePaymentField(errors, payout, "refundRejectionRate", refundRejectionRate);
    validatePaymentField(errors, payout, "refundResponseLossRate", refundResponseLossRate);
  }

  private static void validateRange(Errors errors, String field, Integer min, Integer max) {
    if (min != null && max != null && max < min) {
      errors.rejectValue(field, "range", "대응하는 최소 지연 이상이어야 합니다.");
    }
  }

  private static void validatePaymentField(
      Errors errors, boolean payout, String field, Object value) {
    if (payout && value != null) errors.rejectValue(field, "payout", "PAYOUT에서는 null이어야 합니다.");
    if (!payout && value == null) errors.rejectValue(field, "payment", "PG·간편결제에서는 필수입니다.");
  }
}
