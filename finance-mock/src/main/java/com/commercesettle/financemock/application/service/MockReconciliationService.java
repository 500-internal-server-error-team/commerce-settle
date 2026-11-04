package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.port.ReconciliationReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MockReconciliationService {
  private final ReconciliationReader reader;

  public MockReconciliationService(ReconciliationReader reader) {
    this.reader = reader;
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 10)
  public Map<String, Object> payouts(Instant cutoff, Instant snapshot, String cursor, int size) {
    return transactions(cutoff, snapshot, cursor, size, true);
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 10)
  public Map<String, Object> payments(Instant cutoff, Instant snapshot, String cursor, int size) {
    return transactions(cutoff, snapshot, cursor, size, false);
  }

  private Map<String, Object> transactions(
      Instant cutoff, Instant snapshot, String cursor, int size, boolean payout) {
    Instant now = reader.now();
    Instant fixed = snapshot == null ? now : snapshot;
    if (cutoff.plus(Duration.ofMinutes(10)).isAfter(now))
      throw new com.commercesettle.financemock.application.exception
          .ReconciliationCutoffNotReadyException();
    if (size < 1
        || size > 100
        || fixed.isAfter(now)
        || fixed.isBefore(cutoff.plus(Duration.ofMinutes(10))))
      throw new IllegalArgumentException("Invalid reconciliation boundary");
    UUID after = null;
    int page = 0;
    if (cursor != null) {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 5
          || !parts[0].equals(cutoff.toString())
          || !parts[1].equals(fixed.toString())
          || Integer.parseInt(parts[2]) != size)
        throw new IllegalArgumentException("Invalid cursor boundary");
      after = UUID.fromString(parts[3]);
      page = Integer.parseInt(parts[4]);
    }
    long total = (payout ? reader.count(cutoff, fixed) : reader.paymentCount(cutoff, fixed));
    var rows =
        (payout
            ? reader.payouts(cutoff, fixed, after, size + 1)
            : reader.payments(cutoff, fixed, after, size + 1));
    boolean more = rows.size() > size;
    var items = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (cutoff
                            + "|"
                            + fixed
                            + "|"
                            + size
                            + "|"
                            + items.getLast().get("id")
                            + "|"
                            + (page + 1))
                        .getBytes(StandardCharsets.UTF_8))
            : null;

    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("items", items);
    result.put("page", page);
    result.put("size", size);
    result.put("totalElements", total);
    result.put("totalPages", (total + size - 1) / size);
    result.put("cutoffAt", cutoff.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
    result.put("snapshotAt", fixed.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
    result.put("nextCursor", next);
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
  public java.util.List<Map<String, Object>> lookup(java.util.List<UUID> keys, Instant snapshot) {
    if (keys.size() > 100 || snapshot.isAfter(reader.now()))
      throw new IllegalArgumentException("Invalid evidence query");
    return reader.lookup(keys, snapshot);
  }

  /** 현재 계정과 추가 전용 금융 기록을 같은 DB snapshot에서 대조한다. 과거 잔액으로 표현하지 않는다. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
  public Map<String, Object> integrity() {
    return Map.of(
        "checkedAt",
        reader.now().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
        "checks",
        reader.integrity());
  }
}
