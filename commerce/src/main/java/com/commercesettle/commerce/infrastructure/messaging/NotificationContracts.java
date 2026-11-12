package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public final class NotificationContracts {
  private final ObjectMapper mapper;
  private final Map<String, Schema> schemas = new HashMap<>();

  public NotificationContracts(ObjectMapper source) {
    mapper =
        source
            .copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    try (var input = getClass().getResourceAsStream("/contracts/openapi.json")) {
      var canonical = mapper.readTree(input);
      var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
      for (String type : List.of("PaymentNoticeRequested", "SettlementNoticeRequested")) {
        var root = mapper.createObjectNode();
        root.set("components", canonical.required("components"));
        root.put("$ref", "#/components/schemas/" + type + "Payload");
        schemas.put(
            type,
            registry.getSchema(
                SchemaLocation.of("urn:commerce:notice:" + type),
                root.toString(),
                InputFormat.JSON));
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Notification contracts unavailable", e);
    }
  }

  /** null은 정상적인 다른 금융 이벤트다. 해석 불가 원문은 호출자가 격리한다. */
  public EventEnvelope<JsonNode> decode(ConsumerRecord<String, byte[]> record) {
    try {
      if (!List.of("commerce-settle.payment.v1", "commerce-settle.settlement.v1")
              .contains(record.topic())
          || record.value() == null
          || record.value().length > 1048576) throw new IllegalArgumentException();
      var node = mapper.readTree(record.value());
      for (String field : List.of("eventId", "aggregateId")) uuid(node.required(field));
      if (!node.required("eventType").isTextual()
          || !node.required("occurredAt").isTextual()
          || !node.required("eventVersion").isIntegralNumber()
          || !node.required("eventVersion").canConvertToInt()
          || node.required("eventVersion").intValue() != 1
          || !node.required("aggregateVersion").isIntegralNumber()
          || !node.required("aggregateVersion").canConvertToLong()
          || node.required("aggregateVersion").longValue() < 0
          || !node.required("payload").isObject()) throw new IllegalArgumentException();
      OffsetDateTime.parse(node.required("occurredAt").textValue());
      EventEnvelope<JsonNode> event =
          mapper.readValue(record.value(), new TypeReference<EventEnvelope<JsonNode>>() {});
      Schema schema = schemas.get(event.eventType());
      if (schema == null) return null;
      var p = event.payload();
      if (!schema.validate(p.toString(), InputFormat.JSON).isEmpty())
        throw new IllegalArgumentException();
      String kind = p.required("kind").textValue();
      boolean payment = event.eventType().equals("PaymentNoticeRequested");
      String keyField = payment ? "paymentId" : "batchId";
      uuid(p.required(keyField));
      if (!record
              .topic()
              .equals(payment ? "commerce-settle.payment.v1" : "commerce-settle.settlement.v1")
          || !p.required(keyField).textValue().equals(record.key()))
        throw new IllegalArgumentException();
      UUID business = uuid(p.required("businessRef"));
      UUID expected =
          uuid(
              p.required(
                  payment
                      ? (kind.equals("PAYMENT_COMPLETED") ? "paymentId" : "refundId")
                      : (kind.equals("PAYOUT_COMPLETED") ? "payoutId" : "batchId")));
      if (!business.equals(expected)) throw new IllegalArgumentException();
      if (payment) {
        for (String field : List.of("orderId", "paymentCycleId", "buyerId", "sellerId"))
          uuid(p.required(field));
        for (String field : List.of("totalAmount", "externalAmount", "mileageAmount"))
          requireLong(p.required(field));
        if (Math.addExact(
                p.required("externalAmount").longValue(), p.required("mileageAmount").longValue())
            != p.required("totalAmount").longValue()) throw new IllegalArgumentException();
        boolean balanceNull = p.required("mileageBalanceAfter").isNull();
        if (balanceNull != p.required("walletVersion").isNull()
            || (p.required("mileageAmount").longValue() > 0 && balanceNull))
          throw new IllegalArgumentException();
        if (!balanceNull) {
          requireLong(p.required("mileageBalanceAfter"));
          requireLong(p.required("walletVersion"));
        }
        OffsetDateTime.parse(p.required("processedAt").textValue());
      } else {
        uuid(p.required("sellerId"));
        requireLong(p.required("amount"));
        OffsetDateTime.parse(p.required("cutoffAt").textValue());
        java.time.LocalDate.parse(p.required("batchDate").textValue());
        if (kind.equals("PAYOUT_COMPLETED"))
          OffsetDateTime.parse(p.required("succeededAt").textValue());
      }
      return event;
    } catch (java.io.IOException | RuntimeException e) {
      throw new IllegalArgumentException("Invalid notification event");
    }
  }

  private static UUID uuid(JsonNode value) {
    if (!value.isTextual()
        || !value
            .textValue()
            .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw new IllegalArgumentException();
    return UUID.fromString(value.textValue());
  }

  private static void requireLong(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
      throw new IllegalArgumentException();
  }
}
