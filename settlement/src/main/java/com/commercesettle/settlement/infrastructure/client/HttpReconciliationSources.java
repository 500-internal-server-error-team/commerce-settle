package com.commercesettle.settlement.infrastructure.client;

import com.commercesettle.internalauth.InternalCredentials;
import com.commercesettle.settlement.application.port.ReconciliationSources;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpReconciliationSources implements ReconciliationSources {
  private final RestClient payment;
  private final RestClient finance;
  private final String paymentCredential;
  private final String financeCredential;

  public HttpReconciliationSources(
      RestClient.Builder builder,
      @Value("${settlement.reconciliation.payment-url:http://payment-api:8080}") String paymentUrl,
      @Value("${settlement.reconciliation.payment-credential:}") String paymentCredential,
      @Value("${settlement.payout.mock-base-url}") String financeUrl,
      @Value("${settlement.payout.mock-credential}") String financeCredential) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.payment = builder.clone().baseUrl(paymentUrl).requestFactory(factory).build();
    this.finance = builder.clone().baseUrl(financeUrl).requestFactory(factory).build();
    this.paymentCredential = paymentCredential;
    this.financeCredential = financeCredential;
  }

  @Override
  public Page payments(Instant cutoff, Instant snapshot, String cursor) {
    JsonNode body =
        read(
            payment,
            "/internal/payment-reconciliation-events",
            paymentCredential,
            cutoff,
            snapshot,
            cursor);
    var items = new ArrayList<Event>();
    for (JsonNode e : body.required("items")) {
      if (!java.util.Set.of("PaymentConfirmed", "RefundFinancialCompleted")
          .contains(text(e, "eventType")))
        throw new IllegalStateException("Unexpected source event");
      String publication = text(e, "publicationState");
      Integer partition =
          e.hasNonNull("partition") ? Math.toIntExact(number(e, "partition")) : null;
      Long offset = e.hasNonNull("offset") ? number(e, "offset") : null;
      if (!("PENDING".equals(publication) || "PUBLISHED".equals(publication))
          || ("PUBLISHED".equals(publication)
              && (partition == null
                  || partition < 0
                  || offset == null
                  || offset < 0
                  || time(e, "publishedAt") == null)))
        throw new IllegalStateException("Missing publication evidence");
      if ("PENDING".equals(publication)
          && (partition != null || offset != null || time(e, "publishedAt") != null))
        throw new IllegalStateException("Inconsistent publication evidence");
      items.add(
          new Event(
              uuid(e, "eventId"),
              text(e, "eventType"),
              time(e, "occurredAt"),
              time(e, "createdAt"),
              uuid(e, "paymentId"),
              uuid(e, "refundId"),
              number(e, "externalAmount"),
              number(e, "mileageAmount"),
              number(e, "platformCouponAmount"),
              number(e, "settlementBaseAmount"),
              number(e, "feeAmount"),
              number(e, "sellerNetAmount"),
              publication,
              text(e, "topic"),
              partition,
              offset,
              time(e, "publishedAt"),
              null,
              0,
              null,
              null,
              0,
              0,
              null));
    }
    return new Page(
        time(body, "cutoffAt"),
        time(body, "snapshotAt"),
        number(body, "totalElements"),
        nullable(body, "nextCursor"),
        items);
  }

  @Override
  public Page payouts(Instant cutoff, Instant snapshot, String cursor) {
    JsonNode body =
        read(finance, "/mock/payout-reconciliation", financeCredential, cutoff, snapshot, cursor);
    var items = new ArrayList<Event>();
    for (JsonNode e : body.required("items"))
      items.add(
          new Event(
              uuid(e, "id"),
              "PAYOUT",
              time(e, "createdAt"),
              time(e, "createdAt"),
              null,
              null,
              0,
              0,
              0,
              0,
              0,
              0,
              null,
              null,
              null,
              null,
              null,
              uuid(e, "businessKey"),
              number(e, "amount"),
              text(e, "status"),
              time(e, "succeededAt"),
              number(e, "debitAmount"),
              number(e, "debitCount"),
              text(e, "fundingAccountRef")));
    return new Page(
        time(body, "cutoffAt"),
        time(body, "snapshotAt"),
        number(body, "totalElements"),
        nullable(body, "nextCursor"),
        items);
  }

  private JsonNode read(
      RestClient client,
      String path,
      String credential,
      Instant cutoff,
      Instant snapshot,
      String cursor) {
    JsonNode body =
        client
            .get()
            .uri(
                b -> {
                  var u =
                      b.path(path)
                          .queryParam("cutoffAt", cutoff.toString())
                          .queryParam("size", 100);
                  if (snapshot != null) u.queryParam("snapshotAt", snapshot.toString());
                  if (cursor != null) u.queryParam("cursor", cursor);
                  return u.build();
                })
            .header(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + InternalCredentials.requireFormat("service:settlement", credential))
            .retrieve()
            .body(JsonNode.class);
    if (body == null || !body.hasNonNull("items") || !body.get("items").isArray())
      throw new IllegalStateException("Missing source page");
    return body;
  }

  private static String text(JsonNode node, String field) {
    return node.required(field).requireNonNull().asText();
  }

  private static String nullable(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private static long number(JsonNode node, String field) {
    JsonNode value = node.required(field).requireNonNull();
    if (!value.isIntegralNumber() || !value.canConvertToLong())
      throw new IllegalStateException("Invalid source amount");
    return value.longValue();
  }

  private static UUID uuid(JsonNode node, String field) {
    String s = nullable(node, field);
    return s == null ? null : UUID.fromString(s);
  }

  private static Instant time(JsonNode node, String field) {
    String s = nullable(node, field);
    return s == null ? null : OffsetDateTime.parse(s).toInstant();
  }
}
