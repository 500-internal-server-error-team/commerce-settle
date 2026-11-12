package com.commercesettle.commerce.infrastructure.client;

import com.commercesettle.commerce.application.port.SettlementRetentionGateway;
import com.commercesettle.internalauth.InternalCredentials;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

public class HttpSettlementRetentionGateway implements SettlementRetentionGateway {
  private final RestClient client;
  private final String credential;

  public HttpSettlementRetentionGateway(RestClient client, String credential) {
    this.client = client;
    this.credential = credential;
  }

  @Override
  public List<Closure> lookup(Set<UUID> batchIds) {
    if (batchIds.isEmpty()) return List.of();
    if (batchIds.size() > 1000) throw new IllegalArgumentException("Too many settlement batches");
    try {
      var body =
          client
              .post()
              .uri("/internal/settlement-retention")
              .header(
                  HttpHeaders.AUTHORIZATION,
                  "Bearer " + InternalCredentials.requireFormat("service:commerce", credential))
              .body(Map.of("batchIds", batchIds))
              .retrieve()
              .body(BatchClosure[].class);
      if (body == null) throw new IllegalStateException("Missing settlement retention response");
      return Arrays.stream(body)
          .map(
              row ->
                  new Closure(
                      row.batchId(),
                      row.sellerId(),
                      row.closedAt() == null ? null : row.closedAt().toInstant()))
          .toList();
    } catch (RuntimeException error) {
      throw new SettlementRetentionGateway.UnavailableException();
    }
  }

  private record BatchClosure(
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) UUID batchId,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) UUID sellerId,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          java.time.OffsetDateTime closedAt) {}
}
