package com.commercesettle.financemock.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ReconciliationReader {
  Instant now();

  long count(Instant cutoff, Instant snapshot);

  List<Map<String, Object>> payouts(Instant cutoff, Instant snapshot, UUID after, int size);

  Map<String, Long> integrity();

  long paymentCount(Instant cutoff, Instant snapshot);

  List<Map<String, Object>> payments(Instant cutoff, Instant snapshot, UUID after, int size);

  List<Map<String, Object>> lookup(List<UUID> keys, Instant snapshot);
}
