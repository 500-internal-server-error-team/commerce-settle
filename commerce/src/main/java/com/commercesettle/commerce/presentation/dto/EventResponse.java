package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.SaleEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(name = "Event")
public record EventResponse(
    String name,
    UUID productId,
    long eventPrice,
    long quota,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    UUID id,
    UUID sellerId,
    @Schema(allowableValues = {"DRAFT", "SCHEDULED", "OPEN", "SOLD_OUT", "CLOSED"}) String status,
    long soldCount,
    long reservedCount,
    long version) {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  /** 같은 절대시각을 KST 오프셋으로 내보낸다. */
  public static EventResponse from(SaleEvent event) {
    return new EventResponse(
        event.getName(),
        event.getProductId(),
        event.getEventPrice(),
        event.getQuota(),
        kst(event.getStartsAt()),
        kst(event.getEndsAt()),
        event.getId(),
        event.getSellerId(),
        event.getStatus().name(),
        event.getSoldCount(),
        event.getReservedCount(),
        event.getVersion());
  }

  static OffsetDateTime kst(Instant time) {
    return time.atZone(KST).toOffsetDateTime();
  }
}
