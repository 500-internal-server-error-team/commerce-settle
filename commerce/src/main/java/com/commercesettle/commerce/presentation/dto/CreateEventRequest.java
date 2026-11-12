package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(name = "EventCreate", description = "미공개 이벤트 등록. 재고는 공개 때 배정한다.")
@EventPeriod
public record CreateEventRequest(
    @NotNull @Size(min = 1, max = 80) String name,
    @NotNull UUID productId,
    @Schema(description = "KRW 원 단위 이벤트 가격") @NotNull @Min(1) @Max(10_000_000) Long eventPrice,
    @NotNull @Min(1) @Max(10_000_000) Long quota,
    @NotNull OffsetDateTime startsAt,
    @NotNull OffsetDateTime endsAt)
    implements EventPeriod.Period {}
