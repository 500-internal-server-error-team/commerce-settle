package com.commercesettle.commerce.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 저장소의 동일 OpenAPI payload를 사용해 수신 경계의 타입·필수값·범위를 검사한다. */
public final class OrderResultContracts {
  public static final Set<String> TYPES =
      Set.of(
          "PaymentConfirmed",
          "PaymentDeclined",
          "PaymentCancelled",
          "PaymentCancellationRejected",
          "RefundAccepted",
          "RefundCompleted",
          "RefundFailed");
  private final Map<String, Schema> schemas = new HashMap<>();

  public OrderResultContracts(ObjectMapper mapper) {
    try (var input = OrderResultContracts.class.getResourceAsStream("/contracts/openapi.json")) {
      var canonical = mapper.readTree(input);
      var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
      for (var type : TYPES) {
        var root = mapper.createObjectNode();
        root.set("components", canonical.required("components"));
        root.put("$ref", "#/components/schemas/" + type + "Payload");
        schemas.put(
            type,
            registry.getSchema(
                SchemaLocation.of("urn:commerce:result:" + type),
                root.toString(),
                InputFormat.JSON));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Order result contracts unavailable");
    }
  }

  public void validate(String type, JsonNode payload) {
    if (!schemas.containsKey(type)
        || !schemas.get(type).validate(payload.toString(), InputFormat.JSON).isEmpty())
      throw new IllegalArgumentException("Invalid order result payload");
  }
}
