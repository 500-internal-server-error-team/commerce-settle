package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ProviderReconciliationReader {
  List<Map<String, Object>> lookup(List<UUID> keys, Instant snapshot);

  Map<String, Object> page(Instant cutoff, Instant snapshot, String cursor, int size);
}
