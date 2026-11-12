package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PaymentIntegrityReader {
  Instant now();

  long count(Instant cutoff, Instant snapshot);

  List<Row> page(Instant cutoff, Instant snapshot, UUID after, int size);

  List<Row> byKeys(List<UUID> keys, Instant cutoff, Instant snapshot);

  Map<String, Long> checks();

  record Row(
      UUID id,
      UUID businessKey,
      String providerId,
      String operation,
      long amount,
      String status,
      UUID externalRef,
      UUID originalId) {}
}
