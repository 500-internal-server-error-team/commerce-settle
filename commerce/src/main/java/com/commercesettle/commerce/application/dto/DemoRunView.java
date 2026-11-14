package com.commercesettle.commerce.application.dto;

import com.commercesettle.commerce.application.port.DemoRunStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DemoRunView(
    UUID id,
    UUID sellerId,
    UUID productId,
    UUID eventId,
    int deliverySeconds,
    int refundSeconds,
    Instant createdAt,
    Instant frozenAt,
    List<UUID> orderIds,
    List<UUID> payableOrderIds,
    List<UUID> refundedOrderIds,
    int pendingCount,
    int excludedCount) {
  public static DemoRunView from(DemoRunStore.Run run, List<DemoRunStore.Member> members) {
    var ids = java.util.Set.copyOf(run.orderIds());
    var selected =
        members.stream().filter(m -> run.frozenAt() == null || ids.contains(m.id())).toList();
    var payable =
        selected.stream()
            .filter(m -> m.status().equals("PURCHASE_CONFIRMED"))
            .map(DemoRunStore.Member::id)
            .toList();
    var refunded =
        selected.stream()
            .filter(m -> m.status().equals("REFUNDED"))
            .map(DemoRunStore.Member::id)
            .toList();
    int pending =
        (int)
            selected.stream()
                .filter(
                    m ->
                        !List.of("PURCHASE_CONFIRMED", "REFUNDED", "CANCELLED", "DECLINED")
                            .contains(m.status()))
                .count();
    int excluded =
        (int)
            selected.stream()
                .filter(m -> List.of("CANCELLED", "DECLINED").contains(m.status()))
                .count();
    return new DemoRunView(
        run.id(),
        run.sellerId(),
        run.productId(),
        run.eventId(),
        run.deliverySeconds(),
        run.refundSeconds(),
        run.createdAt(),
        run.frozenAt(),
        run.orderIds(),
        payable,
        refunded,
        pending,
        excluded);
  }
}
