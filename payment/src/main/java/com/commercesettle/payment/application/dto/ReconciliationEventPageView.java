package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.application.port.ReconciliationEventReader.ReconciliationEvent;
import java.time.Instant;
import java.util.List;

/** 정산 대조 페이지. nextCursor는 마지막 페이지에서 null이다. */
public record ReconciliationEventPageView(
    List<ReconciliationEvent> items,
    int page,
    int size,
    long totalElements,
    Instant cutoffAt,
    Instant snapshotAt,
    String nextCursor) {
  public int totalPages() {
    return (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
  }
}
