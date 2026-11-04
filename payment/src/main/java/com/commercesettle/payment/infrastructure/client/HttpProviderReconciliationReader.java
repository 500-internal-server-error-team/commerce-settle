package com.commercesettle.payment.infrastructure.client;

import com.commercesettle.internalauth.InternalCredentials;
import com.commercesettle.payment.application.port.ProviderReconciliationReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpProviderReconciliationReader implements ProviderReconciliationReader {
  private final RestClient client;
  private final String credential;

  public HttpProviderReconciliationReader(
      RestClient.Builder builder,
      @Value("${app.payment-approval.mock-base-url:http://finance-mock:8080}") String url,
      @Value("${app.payment-approval.mock-credential:}") String credential) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    this.client = builder.baseUrl(url).requestFactory(factory).build();
    this.credential = credential;
  }

  private String authorization() {
    return "Bearer " + InternalCredentials.requireFormat("service:payment", credential);
  }

  @Override
  public List<Map<String, Object>> lookup(List<UUID> keys, Instant snapshot) {
    if (keys.isEmpty()) return List.of();
    var value =
        client
            .post()
            .uri("/mock/payment-reconciliation-queries")
            .header(HttpHeaders.AUTHORIZATION, authorization())
            .body(Map.of("businessKeys", keys, "snapshotAt", snapshot.toString()))
            .retrieve()
            .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    if (value == null) throw new IllegalStateException("Missing provider evidence");
    return value;
  }

  @Override
  public Map<String, Object> page(Instant cutoff, Instant snapshot, String cursor, int size) {
    var value =
        client
            .get()
            .uri(
                b -> {
                  var u =
                      b.path("/mock/payment-reconciliation")
                          .queryParam("cutoffAt", cutoff.toString())
                          .queryParam("snapshotAt", snapshot.toString())
                          .queryParam("size", size);
                  if (cursor != null) u.queryParam("cursor", cursor);
                  return u.build();
                })
            .header(HttpHeaders.AUTHORIZATION, authorization())
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    if (value == null) throw new IllegalStateException("Missing provider page");
    return value;
  }
}
