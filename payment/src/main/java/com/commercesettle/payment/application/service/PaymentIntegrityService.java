package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.port.PaymentIntegrityReader;
import com.commercesettle.payment.application.port.PaymentIntegrityReader.Row;
import com.commercesettle.payment.application.port.ProviderReconciliationReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentIntegrityService {
  private final PaymentIntegrityReader local;
  private final ProviderReconciliationReader provider;

  public PaymentIntegrityService(
      PaymentIntegrityReader local, ProviderReconciliationReader provider) {
    this.local = local;
    this.provider = provider;
  }

  /** 자기 DB 읽기를 마친 뒤 한 페이지의 키를 묶어 HTTP로 조회한다. 외부 호출 중 DB 트랜잭션은 없다. */
  public Map<String, Object> read(
      Instant cutoff, Instant snapshot, String cursor, int size, String direction) {
    Instant now = local.now();
    Instant fixed = snapshot == null ? now : snapshot;
    if (cutoff.plus(Duration.ofMinutes(10)).isAfter(now))
      throw new com.commercesettle.payment.domain.exception.ReconciliationCutoffNotReadyException();
    if (size < 1
        || size > 100
        || fixed.isAfter(now)
        || fixed.isBefore(cutoff.plus(Duration.ofMinutes(10))))
      throw new IllegalArgumentException("Invalid reconciliation boundary");
    String suppliedCursor = cursor;
    int pageNumber = 0;
    long total;
    if (cursor != null) {
      if (snapshot == null) throw new IllegalArgumentException("Cursor requires fixed snapshot");
      String[] parts =
          new String(
                  java.util.Base64.getUrlDecoder().decode(cursor),
                  java.nio.charset.StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 6
          || !parts[0].equals(cutoff.toString())
          || !parts[1].equals(fixed.toString())
          || !parts[2].equals(Integer.toString(size))
          || !parts[3].equals(direction))
        throw new IllegalArgumentException("Cursor boundary changed");
      pageNumber = Integer.parseInt(parts[5]);
      cursor =
          new String(
              java.util.Base64.getUrlDecoder().decode(parts[4]),
              java.nio.charset.StandardCharsets.UTF_8);
    }
    List<Row> rows;
    List<Map<String, Object>> remote;
    String next;
    try {
      if ("PAYMENT".equals(direction)) {
        UUID after = cursor == null ? null : UUID.fromString(cursor);
        var found = local.page(cutoff, fixed, after, size + 1);
        boolean more = found.size() > size;
        rows = more ? found.subList(0, size) : found;
        next = more ? rows.getLast().id().toString() : null;
        total = local.count(cutoff, fixed);
        remote = provider.lookup(rows.stream().map(Row::businessKey).distinct().toList(), fixed);
      } else if ("PROVIDER".equals(direction)) {
        var page = provider.page(cutoff, fixed, cursor, size);
        Object raw = page.get("items");
        if (!(raw instanceof List<?> list))
          throw new IllegalStateException("Invalid provider page");
        remote = new ArrayList<>();
        for (Object item : list) {
          if (!(item instanceof Map<?, ?> map))
            throw new IllegalStateException("Invalid provider item");
          var row = new java.util.HashMap<String, Object>();
          map.forEach((k, v) -> row.put(k.toString(), v));
          remote.add(row);
        }
        rows =
            local.byKeys(
                remote.stream()
                    .map(x -> UUID.fromString(x.get("businessKey").toString()))
                    .distinct()
                    .toList(),
                cutoff,
                fixed);
        next = (String) page.get("nextCursor");
        total = ((Number) page.get("totalElements")).longValue();
        pageNumber = ((Number) page.get("page")).intValue();
      } else throw new IllegalArgumentException("Invalid reconciliation direction");
    } catch (org.springframework.web.client.RestClientException e) {
      throw new org.springframework.dao.TransientDataAccessResourceException(
          "Provider evidence unavailable", e);
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (Row row : rows) {
      var found = remote.stream().filter(x -> sameKey(row, x)).toList();
      if ("CANCELLED".equals(row.status())) {
        results.add(
            result(
                row.id().toString(),
                found.isEmpty() ? "MATCH" : "DEFECT",
                found.isEmpty()
                    ? "CANCELLED_WITHOUT_PROVIDER_TRANSACTION"
                    : "CANCELLED_WITH_PROVIDER_TRANSACTION",
                0));
        continue;
      }
      if (!List.of("APPROVED", "SUCCEEDED", "DECLINED").contains(row.status())) {
        results.add(result(row.id().toString(), "IN_PROGRESS", "FINANCIAL_RESULT_PENDING", 0));
        continue;
      }
      if (found.size() != 1) {
        results.add(
            result(
                row.id().toString(),
                "DEFECT",
                "PROVIDER_TRANSACTION_MISSING_OR_DUPLICATED",
                row.amount()));
        continue;
      }
      var actual = found.getFirst();
      Object rawAmount = actual.get("amount");
      if (!(rawAmount instanceof Integer
          || rawAmount instanceof Long
          || rawAmount instanceof java.math.BigInteger))
        throw new org.springframework.dao.TransientDataAccessResourceException(
            "Provider amount is not an integer");
      if ("SUCCEEDED".equals(actual.get("status"))) {
        if (!(actual.get("succeededAt") instanceof String time))
          throw new org.springframework.dao.TransientDataAccessResourceException(
              "Provider success time missing");
        if (java.time.OffsetDateTime.parse(time).toInstant().isAfter(cutoff)) {
          results.add(result(row.id().toString(), "MATCH", "PROVIDER_SUCCEEDED_AFTER_CUTOFF", 0));
          continue;
        }
      }
      long delta = ((Number) rawAmount).longValue() - row.amount();
      boolean succeeded = List.of("APPROVED", "SUCCEEDED").contains(row.status());
      boolean match =
          delta == 0
              && (succeeded ? "SUCCEEDED" : "DECLINED").equals(actual.get("status"))
              && (!succeeded
                  || row.externalRef() != null
                      && row.externalRef().toString().equals(actual.get("id")))
              && (row.originalId() == null
                  || row.originalId().toString().equals(actual.get("originalId")));
      results.add(
          result(
              row.id().toString(),
              match ? "MATCH" : "DEFECT",
              match ? "PROVIDER_MATCH" : "PROVIDER_AMOUNT_OR_REFERENCE_MISMATCH",
              delta));
    }
    if ("PROVIDER".equals(direction))
      for (var actual : remote) {
        if (rows.stream().noneMatch(row -> sameKey(row, actual)))
          results.add(
              result(
                  actual.get("id").toString(),
                  "DEFECT",
                  "ORPHAN_PROVIDER_TRANSACTION",
                  ((Number) actual.get("amount")).longValue()));
      }
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("items", results);
    response.put("page", pageNumber);
    response.put("size", size);
    response.put("totalElements", total);
    response.put("totalPages", (total + size - 1) / size);
    response.put("cutoffAt", cutoff.atOffset(java.time.ZoneOffset.ofHours(9)));
    response.put("snapshotAt", fixed.atOffset(java.time.ZoneOffset.ofHours(9)));
    response.put(
        "nextCursor",
        next == null
            ? null
            : java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (cutoff
                            + "|"
                            + fixed
                            + "|"
                            + size
                            + "|"
                            + direction
                            + "|"
                            + java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                    next.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            + "|"
                            + (pageNumber + 1))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    response.put("direction", direction);
    if (suppliedCursor == null) {
      response.put("checkedAt", local.now().atOffset(java.time.ZoneOffset.ofHours(9)));
      response.put("currentChecks", local.checks());
    }
    return response;
  }

  private boolean sameKey(Row row, Map<String, Object> actual) {
    return row.businessKey().toString().equals(actual.get("businessKey"))
        && java.util.Objects.equals(row.providerId(), actual.get("providerId"))
        && row.operation().equals(actual.get("operation"));
  }

  private Map<String, Object> result(String key, String status, String reason, long difference) {
    return Map.of("key", key, "status", status, "reason", reason, "difference", difference);
  }
}
