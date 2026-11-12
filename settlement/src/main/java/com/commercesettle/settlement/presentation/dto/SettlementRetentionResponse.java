package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.service.SettlementRetentionService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record SettlementRetentionResponse(UUID batchId, UUID sellerId, OffsetDateTime closedAt) {
  public static SettlementRetentionResponse from(SettlementRetentionService.Closure closure) {
    return new SettlementRetentionResponse(
        closure.batchId(),
        closure.sellerId(),
        closure.closedAt() == null
            ? null
            : closure.closedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
  }
}
