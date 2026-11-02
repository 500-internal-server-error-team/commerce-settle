package com.commercesettle.settlement.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 발행 원문이 OpenAPI {@code EventEnvelope}(종류별 payload, additionalProperties: false)를 만족하는지 검사한다. */
public final class EventContract {
  private static final Schema ENVELOPE = load();

  private EventContract() {}

  public static void assertValid(byte[] body) {
    assertThat(ENVELOPE.validate(new String(body, StandardCharsets.UTF_8), InputFormat.JSON))
        .as("EventEnvelope contract")
        .isEmpty();
  }

  private static Schema load() {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in = EventContract.class.getResourceAsStream("/contracts/openapi.json")) {
      ObjectNode root = mapper.createObjectNode();
      root.set("components", mapper.readTree(in).path("components"));
      root.put("$ref", "#/components/schemas/EventEnvelope");
      return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(
              SchemaLocation.of("urn:test:settlement-events"), root.toString(), InputFormat.JSON);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
