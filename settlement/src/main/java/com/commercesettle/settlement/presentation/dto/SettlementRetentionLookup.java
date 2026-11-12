package com.commercesettle.settlement.presentation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.UniqueElements;

public record SettlementRetentionLookup(
    @NotNull @Size(min = 1, max = 1000) @UniqueElements List<@NotNull UUID> batchIds) {
  @JsonAnySetter
  public void rejectUnknown(String field, Object value) {
    throw new IllegalArgumentException("Unknown retention lookup field");
  }
}
