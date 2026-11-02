package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.SaleEventService.GaugeView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(
    name = "EventGauge",
    description = "percent=floor(100×soldCount/quota). 예약만으로 100%나 매진을 표시하지 않는다.")
public record EventGaugeResponse(
    UUID eventId,
    long quota,
    long soldCount,
    long reservedCount,
    long remainingQuantity,
    int percent,
    @Schema(allowableValues = {"OPEN", "WAITING_PAYMENTS", "SOLD_OUT", "NOT_STARTED", "CLOSED"})
        String admission,
    OffsetDateTime asOf) {
  public static EventGaugeResponse from(GaugeView view) {
    var gauge = view.gauge();
    return new EventGaugeResponse(
        view.eventId(),
        gauge.quota(),
        gauge.soldCount(),
        gauge.reservedCount(),
        gauge.remainingQuantity(),
        gauge.percent(),
        gauge.admission().name(),
        EventResponse.kst(view.asOf()));
  }
}
