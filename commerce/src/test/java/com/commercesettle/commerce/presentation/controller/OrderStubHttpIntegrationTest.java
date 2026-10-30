package com.commercesettle.commerce.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.infrastructure.config.HttpJsonConfiguration;
import com.commercesettle.commerce.infrastructure.config.OpenApiConfiguration;
import com.commercesettle.commerce.infrastructure.config.OrderStubConfiguration;
import com.commercesettle.commerce.presentation.exception.CommerceExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = OrderStubHttpIntegrationTest.TestApplication.class,
    properties = {
      "app.order-stub.enabled=true",
      "app.order-stub.seed=67",
      "management.server.port=-1",
      "management.tracing.enabled=true",
      "management.zipkin.tracing.export.enabled=false",
      "management.tracing.sampling.probability=1.0"
    })
@AutoConfigureObservability(metrics = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderStubHttpIntegrationTest {
  private static final String BUYER = "00000000-0000-4000-8000-000000000001";
  private static final String OTHER = "00000000-0000-4000-8000-000000000011";
  private static final String BODY =
      """
      {"productId":"00000000-0000-4000-8000-000000000003","quantity":1,"mileageAmount":0,
       "expectedProductVersion":0,"expectedTotalAmount":23000,
       "externalMethod":"CREDIT_CARD","methodAccountRef":"stub-card"}
      """;
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  private SchemaRegistry schemas;
  private SchemaRegistry canonicalSchemas;
  private JsonNode canonicalApi;
  private JsonNode generatedApi;

  @Test
  void rejectsUnknownProductFromInventoryStub() throws Exception {
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(BODY);
    body.put("productId", UUID.randomUUID().toString());
    problem(
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            UUID.randomUUID().toString(),
            body.toString()),
        404,
        "COMMON_RESOURCE_NOT_FOUND");
  }

  @Test
  void createsAndReplaysThenReadsSameOwnedSnapshotThroughRealHttp() throws Exception {
    String key = UUID.randomUUID().toString();
    var created = call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", key, BODY);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    JsonNode order = json(created);
    assertThat(order.size()).isEqualTo(28);
    matchesSchema("OrderStubCreate", mapper.readTree(BODY));
    matchesSchema("Order", order);
    assertThat(order.path("productName").asText()).isEqualTo("수납 상자");
    assertThat(order.path("status").asText()).isEqualTo("PAYMENT_PENDING");
    assertThat(order.path("createdAt").asText()).isEqualTo("2026-01-20T10:00:00.123456+09:00");
    assertThat(order.path("deliveryBusinessDays").asInt()).isIn(2, 3);
    for (String name :
        List.of(
            "paymentId",
            "paymentConfirmedAt",
            "shippedAt",
            "deliveryDueAt",
            "deliveredAt",
            "refundDeadlineAt",
            "purchaseConfirmedAt")) {
      assertThat(order.get(name).isNull()).as(name).isTrue();
    }
    String location = created.getHeaders().getLocation().toString();
    assertThat(location).isEqualTo("/api/orders/" + order.path("id").asText());
    var replay = call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", key, BODY);
    assertThat(replay.getStatusCode().value()).isEqualTo(201);
    assertThat(json(replay)).isEqualTo(order);
    assertThat(json(call(HttpMethod.GET, location, BUYER, "BUYER", null, null))).isEqualTo(order);
    var conflict =
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            key,
            BODY.replace("\"quantity\":1", "\"quantity\":2"));
    problem(conflict, 409, "COMMON_IDEMPOTENCY_CONFLICT");
    problem(
        call(HttpMethod.GET, location, OTHER, "BUYER", null, null),
        404,
        "COMMON_RESOURCE_NOT_FOUND");
  }

  @Test
  void listsVariedSamplesWithStableDetailAndOwnedPagination() throws Exception {
    JsonNode page = json(call(HttpMethod.GET, "/api/orders?size=100", OTHER, "BUYER", null, null));
    assertThat(page.path("totalElements").asInt()).isEqualTo(10);
    matchesSchema("OrderPage", page);
    for (JsonNode order : page.path("items")) {
      assertThat(order.path("buyerId").asText()).isEqualTo(OTHER);
      matchesSchema("Order", order);
      String path = "/api/orders/" + order.path("id").asText();
      assertThat(json(call(HttpMethod.GET, path, OTHER, "BUYER", null, null))).isEqualTo(order);
    }
    var large =
        call(HttpMethod.GET, "/api/orders?page=2147483647&size=100", OTHER, "BUYER", null, null);
    assertThat(json(large).path("items").size()).isZero();
    problem(
        call(HttpMethod.GET, "/api/orders?size=101", BUYER, "BUYER", null, null),
        400,
        "COMMON_VALIDATION_FAILED");
  }

  @Test
  void rejectsMissingIdentityWrongRoleAndInvalidHeaders() throws Exception {
    problem(
        call(HttpMethod.GET, "/api/orders", null, null, null, null),
        401,
        "COMMON_AUTHENTICATION_REQUIRED");
    problem(
        call(HttpMethod.GET, "/api/orders", BUYER, null, null, null),
        401,
        "COMMON_AUTHENTICATION_REQUIRED");
    problem(
        call(HttpMethod.GET, "/api/orders", BUYER, "SELLER", null, null),
        403,
        "COMMON_ROLE_FORBIDDEN");
    problem(
        call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", null, BODY),
        400,
        "COMMON_VALIDATION_FAILED");
    problem(
        call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", "short", BODY),
        400,
        "COMMON_VALIDATION_FAILED");
  }

  @Test
  void rejectsInvalidJsonShapeAndMissingNullableKeysButAllowsExplicitNull() throws Exception {
    for (String field : List.of("externalMethod", "methodAccountRef")) {
      var body = mapper.readTree(BODY).deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove(field);
      assertThat(schema("OrderStubCreate").validate(body.toString(), InputFormat.JSON))
          .isNotEmpty();
      problem(
          call(
              HttpMethod.POST,
              "/api/orders",
              BUYER,
              "BUYER",
              UUID.randomUUID().toString(),
              body.toString()),
          400,
          "COMMON_VALIDATION_FAILED");
    }
    for (String body :
        List.of(
            BODY.replace("\"quantity\":1", "\"quantity\":0"),
            BODY.replace("\"quantity\":1", "\"quantity\":1.5"),
            BODY.replace("\"quantity\":1", "\"quantity\":\"1\""),
            BODY.replace("\"quantity\":1", "\"quantity\":1,\"unitPrice\":1"))) {
      problem(
          call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", UUID.randomUUID().toString(), body),
          400,
          "COMMON_VALIDATION_FAILED");
    }
  }

  @Test
  void rejectsMismatchedPricesAndVersions() throws Exception {
    for (String body :
        List.of(
            BODY.replace("\"expectedTotalAmount\":23000", "\"expectedTotalAmount\":1"),
            BODY.replace("\"expectedProductVersion\":0", "\"expectedProductVersion\":1"))) {
      problem(
          call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", UUID.randomUUID().toString(), body),
          409,
          "COMMON_VERSION_CONFLICT");
    }
  }

  @Test
  void rejectsTodoCouponEventAndManualAddressInputs() throws Exception {
    for (String field : List.of("buyerCouponId", "eventId", "expectedEventVersion", "addressId")) {
      var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(BODY);
      body.putNull(field);
      assertThat(schema("OrderStubCreate").validate(body.toString(), InputFormat.JSON))
          .isNotEmpty();
      problem(
          call(
              HttpMethod.POST,
              "/api/orders",
              BUYER,
              "BUYER",
              UUID.randomUUID().toString(),
              body.toString()),
          400,
          "COMMON_VALIDATION_FAILED");
    }
  }

  @Test
  void acceptsNullExternalMethodForFullMileageAndPublishesDistinctStubSchema() throws Exception {
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(BODY);
    body.put("mileageAmount", 23_000).putNull("externalMethod").putNull("methodAccountRef");
    var created =
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            UUID.randomUUID().toString(),
            body.toString());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(json(created).path("externalAmount").asLong()).isZero();
    assertThat(json(created).path("couponAmount").asLong()).isZero();
    assertThat(json(created).get("eventId").isNull()).isTrue();
    matchesSchema("OrderStubCreate", body);
    matchesSchema("Order", json(created));
    var api = http.getForEntity("/v3/api-docs", String.class);
    assertThat(api.getStatusCode().value()).isEqualTo(200);
    var properties =
        json(api).path("components").path("schemas").path("OrderStubCreate").path("properties");
    assertThat(properties.size()).isEqualTo(7);
    assertThat(properties.has("addressId")).isFalse();
    assertThat(properties.has("eventId")).isFalse();
    assertThat(properties.has("buyerCouponId")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1", "2", "\"0\"", "\"2\"", "true", "false"})
  void rejectsNonLabelExternalMethodsBeforeCreatingOrder(String value) throws Exception {
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(BODY);
    body.set("externalMethod", mapper.readTree(value));
    assertThat(schema("OrderStubCreate").validate(body.toString(), InputFormat.JSON)).isNotEmpty();
    problem(
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            UUID.randomUUID().toString(),
            body.toString()),
        400,
        "COMMON_VALIDATION_FAILED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.5", "true", "false"})
  void rejectsNonTextMethodAccountReferences(String value) throws Exception {
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(BODY);
    body.set("methodAccountRef", mapper.readTree(value));
    assertThat(schema("OrderStubCreate").validate(body.toString(), InputFormat.JSON)).isNotEmpty();
    problem(
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            UUID.randomUUID().toString(),
            body.toString()),
        400,
        "COMMON_VALIDATION_FAILED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREDIT_CARD", "DEBIT_CARD", "EASY_PAY"})
  void acceptsDeclaredMethodLabelsAndMatchingSchemas(String method) throws Exception {
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(BODY);
    body.put("externalMethod", method);
    matchesSchema("OrderStubCreate", body);
    var created =
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            UUID.randomUUID().toString(),
            body.toString());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    matchesSchema("Order", json(created));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", ""})
  void rejectsMalformedActorIdentity(String actor) throws Exception {
    problem(
        call(HttpMethod.GET, "/api/orders", actor, "BUYER", null, null),
        401,
        "COMMON_AUTHENTICATION_REQUIRED");
  }

  @Test
  void orderSchemaRequiresAllDeclaredResponseKeysIncludingNullableFields() throws Exception {
    JsonNode page = json(call(HttpMethod.GET, "/api/orders?size=100", OTHER, "BUYER", null, null));
    var order = (com.fasterxml.jackson.databind.node.ObjectNode) page.path("items").get(0);
    matchesSchema("Order", order);
    var fields = order.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      var missing = order.deepCopy();
      missing.remove(field);
      assertThat(schema("Order").validate(missing.toString(), InputFormat.JSON))
          .as(field)
          .isNotEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Order", "OrderPage", "OrderStubCreate", "Problem", "FieldError"})
  void generatedSchemasPreserveCanonicalContracts(String name) throws Exception {
    JsonNode sample = contractSample(name);
    matchesSchema(name, sample);
    JsonNode expected = canonicalApi.path("components").path("schemas").path(name);
    JsonNode actual = generatedApi.path("components").path("schemas").path(name);

    assertThat(actual.path("properties").properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrderElementsOf(
            expected.path("properties").properties().stream().map(Map.Entry::getKey).toList());
    assertThat(elements(actual.path("required"))).isEqualTo(elements(expected.path("required")));
    assertThat(actual.get("additionalProperties")).isEqualTo(expected.get("additionalProperties"));
    assertThat(schemaTypes(actual)).isEqualTo(schemaTypes(expected));
    for (var property : expected.path("properties").properties()) {
      String field = property.getKey();
      JsonNode declared = actual.path("properties").path(field);
      assertThat(schemaTypes(declared))
          .as(name + "." + field + " type")
          .isEqualTo(schemaTypes(property.getValue()));
      JsonNode expectedValue = nonNullSchema(property.getValue());
      JsonNode actualValue = nonNullSchema(declared);
      for (String keyword :
          List.of("minimum", "maximum", "minLength", "maxLength", "pattern", "items")) {
        assertThat(actualValue.get(keyword))
            .as(name + "." + field + "." + keyword)
            .isEqualTo(expectedValue.get(keyword));
      }
      if (expectedValue.has("format")) {
        assertThat(actualValue.get("format"))
            .as(name + "." + field + ".format")
            .isEqualTo(expectedValue.get("format"));
      }
      Set<JsonNode> expectedValues = elements(expectedValue.path("enum"));
      Set<JsonNode> actualValues = elements(actualValue.path("enum"));
      actualValues.remove(mapper.getNodeFactory().nullNode());
      assertThat(actualValues).as(name + "." + field + " enum").isEqualTo(expectedValues);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "OrderPage,totalPages,",
    "Order,status,'\"UNKNOWN\"'",
    "Order,fulfillmentStatus,'\"UNKNOWN\"'",
    "Order,deliveryBusinessDays,4",
    "Order,grossAmount,-1",
    "Order,id,",
    "Problem,traceId,'\"\"'",
    "Problem,status,200",
    "FieldError,field,'\"\"'",
    "FieldError,reason,"
  })
  void bothContractsRejectMissingKeysAndInvalidResponseValues(
      String name, String field, String value) throws Exception {
    ObjectNode invalid = (ObjectNode) contractSample(name);
    if (value == null) {
      invalid.remove(field);
    } else {
      invalid.set(field, mapper.readTree(value));
    }
    rejectedByContracts(name, invalid);
  }

  @Test
  void pageContractsValidateNestedOrders() throws Exception {
    ObjectNode page = (ObjectNode) contractSample("OrderPage");
    ((ObjectNode) page.path("items").get(0)).put("status", "UNKNOWN");

    rejectedByContracts("OrderPage", page);
  }

  private JsonNode contractSample(String name) throws Exception {
    if (name.equals("Problem") || name.equals("FieldError")) {
      var response =
          call(
              HttpMethod.POST,
              "/api/orders",
              BUYER,
              "BUYER",
              UUID.randomUUID().toString(),
              BODY.replace("\"quantity\":1", "\"quantity\":0"));
      JsonNode problem = json(response);
      return name.equals("Problem") ? problem : problem.path("errors").get(0);
    }
    if (name.equals("OrderStubCreate")) {
      return mapper.readTree(BODY);
    }
    JsonNode page = json(call(HttpMethod.GET, "/api/orders?size=100", OTHER, "BUYER", null, null));
    return name.equals("OrderPage") ? page : page.path("items").get(0);
  }

  @ParameterizedTest
  @CsvSource({"/api/orders,post", "/api/orders,get", "/api/orders/{orderId},get"})
  void operationsPreserveCanonicalResponsesAndHeaders(String path, String method) throws Exception {
    schema("Order");
    canonicalSchema("Order");
    assertOperation(path, method, generatedApi.path("paths").path(path).path(method));
  }

  private void assertOperation(String path, String method, JsonNode actual) {
    JsonNode expected = canonicalApi.path("paths").path(path).path(method);
    assertThat(actual.path("operationId")).isEqualTo(expected.path("operationId"));
    Set<String> codes = new HashSet<>();
    expected.path("responses").fieldNames().forEachRemaining(codes::add);
    // 프레임워크의 405/406 및 예상하지 못한 500도 같은 ProblemDetail 계약이다.
    codes.add("default");
    assertThat(actual.path("responses").properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrderElementsOf(codes);
    for (String code : codes) {
      JsonNode response = actual.path("responses").path(code);
      JsonNode contract = expected.path("responses").path(code.equals("default") ? "400" : code);
      assertThat(response.path("content").properties().stream().map(Map.Entry::getKey).toList())
          .as(method + " " + path + " " + code)
          .containsExactlyInAnyOrderElementsOf(
              contract.path("content").properties().stream().map(Map.Entry::getKey).toList());
      for (var media : contract.path("content").properties()) {
        assertThat(response.path("content").path(media.getKey()).path("schema"))
            .isEqualTo(media.getValue().path("schema"));
      }
      assertThat(response.path("headers").properties().stream().map(Map.Entry::getKey).toList())
          .containsExactlyInAnyOrderElementsOf(
              contract.path("headers").properties().stream().map(Map.Entry::getKey).toList());
      for (var header : contract.path("headers").properties()) {
        assertThat(response.path("headers").path(header.getKey()).path("schema"))
            .as(code + " " + header.getKey())
            .isEqualTo(header.getValue().path("schema"));
      }
    }
    if (method.equals("post")) {
      assertThat(
              actual
                  .path("responses")
                  .path("201")
                  .path("headers")
                  .path("Location")
                  .path("required")
                  .asBoolean())
          .isTrue();
      assertThat(
              actual
                  .path("responses")
                  .path("503")
                  .path("headers")
                  .path("Retry-After")
                  .path("required")
                  .asBoolean())
          .isFalse();
      assertThat(
              actual
                  .path("responses")
                  .path("503")
                  .path("headers")
                  .path("Retry-After")
                  .path("description")
                  .asText())
          .isEqualTo(
              expected
                  .path("responses")
                  .path("503")
                  .path("headers")
                  .path("Retry-After")
                  .path("description")
                  .asText());
    }
  }

  @ParameterizedTest
  @CsvSource({"/api/orders,post", "/api/orders,get", "/api/orders/{orderId},get"})
  void operationsPreserveCanonicalParameterContracts(String path, String method) throws Exception {
    schema("Order");
    canonicalSchema("Order");
    JsonNode actual = generatedApi.path("paths").path(path).path(method);
    for (JsonNode expected :
        canonicalApi.path("paths").path(path).path(method).path("parameters")) {
      JsonNode parameter =
          java.util.stream.StreamSupport.stream(actual.path("parameters").spliterator(), false)
              .filter(
                  value ->
                      value.path("name").equals(expected.path("name"))
                          && value.path("in").equals(expected.path("in")))
              .findFirst()
              .orElseThrow();
      assertThat(parameter).isNotNull();
      assertThat(parameter.path("required").asBoolean())
          .isEqualTo(expected.path("required").asBoolean());
      for (var keyword : expected.path("schema").properties()) {
        assertThat(parameter.path("schema").get(keyword.getKey()))
            .as(expected.path("name") + "." + keyword.getKey())
            .isEqualTo(keyword.getValue());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"status", "location", "media", "body", "retryAfter"})
  void operationComparisonRejectsResponseContractMutations(String mutation) throws Exception {
    schema("Order");
    canonicalSchema("Order");
    ObjectNode operation = generatedApi.path("paths").path("/api/orders").path("post").deepCopy();
    ObjectNode responses = (ObjectNode) operation.path("responses");
    switch (mutation) {
      case "status" -> responses.set("200", responses.remove("201"));
      case "location" -> ((ObjectNode) responses.path("201").path("headers")).remove("Location");
      case "media" -> {
        ObjectNode content = (ObjectNode) responses.path("201").path("content");
        content.set("*/*", content.remove("application/json"));
      }
      case "body" ->
          ((ObjectNode)
                  responses.path("201").path("content").path("application/json").path("schema"))
              .put("$ref", "#/components/schemas/OrderPage");
      case "retryAfter" ->
          ((ObjectNode) responses.path("503").path("headers")).remove("Retry-After");
      default -> throw new IllegalArgumentException(mutation);
    }
    assertThatThrownBy(() -> assertOperation("/api/orders", "post", operation))
        .isInstanceOf(AssertionError.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "bad uri"})
  void actualResponseComparisonRejectsMissingOrMalformedLocation(String location) throws Exception {
    var created =
        call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", UUID.randomUUID().toString(), BODY);
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(created.getHeaders());
    if (location.isEmpty()) headers.remove(HttpHeaders.LOCATION);
    else headers.set(HttpHeaders.LOCATION, location);
    var invalid = new ResponseEntity<>(created.getBody(), headers, created.getStatusCode());
    assertThatThrownBy(() -> matchesOperationResponse(invalid, HttpMethod.POST, "/api/orders"))
        .isInstanceOf(AssertionError.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 100, 101})
  void unknownFieldErrorsRespectFieldNameBounds(int length) throws Exception {
    ObjectNode body = (ObjectNode) mapper.readTree(BODY);
    String field = "f".repeat(length);
    body.put(field, 1);
    var response =
        call(
            HttpMethod.POST,
            "/api/orders",
            BUYER,
            "BUYER",
            UUID.randomUUID().toString(),
            body.toString());
    problem(response, 400, "COMMON_VALIDATION_FAILED");
    assertThat(json(response).path("errors").get(0).path("field").asText())
        .isEqualTo(length == 100 ? field : "request");
  }

  @Test
  void problemCarriesActualInboundTraceAndFrameworkStatus() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    String traceId = "0123456789abcdef0123456789abcdef";
    headers.set("traceparent", "00-" + traceId + "-0123456789abcdef-01");
    var response =
        http.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    matchesOperationResponse(response, HttpMethod.GET, "/api/orders");
    problem(response, 401, "COMMON_AUTHENTICATION_REQUIRED");
    assertThat(json(response).path("traceId").asText()).isEqualTo(traceId);
    var unsupported = call(HttpMethod.PUT, "/api/orders", BUYER, "BUYER", null, null);
    problem(unsupported, 405, "COMMON_METHOD_NOT_ALLOWED");
    assertThat(unsupported.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
  }

  private void matchesOperationResponse(
      ResponseEntity<String> response, HttpMethod method, String requestPath) throws Exception {
    schema("Order");
    canonicalSchema("Order");
    String instance = requestPath.split("\\?", 2)[0];
    String path = instance.equals("/api/orders") ? instance : "/api/orders/{orderId}";
    String code = String.valueOf(response.getStatusCode().value());
    String media = response.getHeaders().getContentType().toString().split(";", 2)[0];
    for (JsonNode api : List.of(canonicalApi, generatedApi)) {
      JsonNode operation =
          api.path("paths").path(path).path(method.name().toLowerCase(java.util.Locale.ROOT));
      JsonNode declared = operation.path("responses").path(code);
      if (declared.isMissingNode() && code.equals("405")) {
        // 정식 명세의 공통 오류 구조를 사용하고 생성 명세의 default 응답도 확인한다.
        declared =
            api == canonicalApi
                ? canonicalApi.path("paths").path(path).path("get").path("responses").path("400")
                : generatedApi
                    .path("paths")
                    .path(path)
                    .path("get")
                    .path("responses")
                    .path("default");
      }
      assertThat(declared.isMissingNode()).as(method + " " + path + " " + code).isFalse();
      assertThat(declared.path("content").has(media)).as(media).isTrue();
      String ref = declared.path("content").path(media).path("schema").path("$ref").asText();
      assertThat(ref).startsWith("#/components/schemas/");
      String name = ref.substring(ref.lastIndexOf('/') + 1);
      Schema contract = api == canonicalApi ? canonicalSchema(name) : schema(name);
      assertThat(
              contract.validate(
                  response.getBody(),
                  InputFormat.JSON,
                  context ->
                      context.executionConfig(config -> config.formatAssertionsEnabled(true))))
          .isEmpty();
      if (code.equals("201")) {
        JsonNode header = declared.path("headers").path("Location");
        assertThat(header.isMissingNode()).isFalse();
        assertThat(response.getHeaders().get(HttpHeaders.LOCATION)).hasSize(1);
        String value = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        SchemaRegistry registry = api == canonicalApi ? canonicalSchemas : schemas;
        String base = api == canonicalApi ? "urn:commerce:canonical" : "urn:commerce:order-stub";
        Schema headerSchema =
            registry.getSchema(
                SchemaLocation.of(
                    base + "#/paths/~1api~1orders/post/responses/201/headers/Location/schema"));
        assertThat(
                headerSchema.validate(
                    mapper.writeValueAsString(value),
                    InputFormat.JSON,
                    context ->
                        context.executionConfig(config -> config.formatAssertionsEnabled(true))))
            .isEmpty();
        assertThat(value).isEqualTo("/api/orders/" + json(response).path("id").asText());
      }
    }
    if (response.getStatusCode().isError()) {
      JsonNode body = json(response);
      assertThat(body.path("status").asInt()).isEqualTo(response.getStatusCode().value());
      assertThat(body.path("instance").asText()).isEqualTo(instance);
      assertThat(body.path("traceId").asText()).isNotBlank();
    }
  }

  private Set<JsonNode> elements(JsonNode values) {
    Set<JsonNode> result = new HashSet<>();
    values.forEach(result::add);
    return result;
  }

  private Set<String> schemaTypes(JsonNode definition) {
    Set<String> result = new HashSet<>();
    JsonNode type = definition.path("type");
    if (type.isArray()) {
      type.forEach(value -> result.add(value.asText()));
    } else if (type.isTextual()) {
      result.add(type.asText());
    }
    for (String union : List.of("anyOf", "oneOf")) {
      definition.path(union).forEach(value -> result.addAll(schemaTypes(value)));
    }
    return result;
  }

  private JsonNode nonNullSchema(JsonNode definition) {
    for (JsonNode option : definition.path("anyOf")) {
      if (!option.path("type").asText().equals("null")) {
        return option;
      }
    }
    return definition;
  }

  private Schema canonicalSchema(String name) throws Exception {
    if (canonicalSchemas == null) {
      try (var input = getClass().getResourceAsStream("/contracts/openapi.json")) {
        assertThat(input).as("정식 OpenAPI 테스트 리소스").isNotNull();
        canonicalApi = mapper.readTree(input);
      }
      ObjectNode definitions = (ObjectNode) canonicalApi.path("components").path("schemas");
      ObjectNode stub = definitions.path("OrderCreate").deepCopy();
      List<String> unsupported =
          List.of("eventId", "buyerCouponId", "addressId", "expectedEventVersion");
      ((ObjectNode) stub.path("properties")).remove(unsupported);
      var required = mapper.createArrayNode();
      for (JsonNode field : stub.path("required")) {
        if (!unsupported.contains(field.asText())) {
          required.add(field);
        }
      }
      stub.set("required", required);
      definitions.set("OrderStubCreate", stub);
      canonicalSchemas =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder ->
                  builder.schemas(Map.of("urn:commerce:canonical", canonicalApi.toString())));
    }
    return canonicalSchemas.getSchema(
        SchemaLocation.of("urn:commerce:canonical#/components/schemas/" + name));
  }

  private void rejectedByContracts(String name, JsonNode payload) throws Exception {
    for (Schema contract : List.of(schema(name), canonicalSchema(name))) {
      assertThat(
              contract.validate(
                  payload.toString(),
                  InputFormat.JSON,
                  context ->
                      context.executionConfig(config -> config.formatAssertionsEnabled(true))))
          .as(name)
          .isNotEmpty();
    }
  }

  private Schema schema(String name) throws Exception {
    if (schemas == null) {
      var document = http.getForEntity("/v3/api-docs", String.class);
      assertThat(document.getStatusCode().value()).isEqualTo(200);
      assertThat(json(document).path("openapi").asText()).startsWith("3.1.");
      generatedApi = json(document);
      // 문서와 참조를 메모리에 등록해 외부 URL을 조회하지 않는다.
      schemas =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder -> builder.schemas(Map.of("urn:commerce:order-stub", document.getBody())));
    }
    return schemas.getSchema(
        SchemaLocation.of("urn:commerce:order-stub#/components/schemas/" + name));
  }

  private void matchesSchema(String name, JsonNode payload) throws Exception {
    for (Schema contract : List.of(schema(name), canonicalSchema(name))) {
      assertThat(
              contract.validate(
                  payload.toString(),
                  InputFormat.JSON,
                  context ->
                      context.executionConfig(config -> config.formatAssertionsEnabled(true))))
          .as(name)
          .isEmpty();
    }
  }

  private ResponseEntity<String> call(
      HttpMethod method, String path, String actor, String kind, String key, String body)
      throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (actor != null) {
      headers.set(AuthenticatedActorResolver.ACTOR_ID, actor);
    }
    if (kind != null) {
      headers.set(AuthenticatedActorResolver.ACTOR_KIND, kind);
    }
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    var response = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    matchesOperationResponse(response, method, path);
    return response;
  }

  private JsonNode json(ResponseEntity<String> response) throws Exception {
    return mapper.readTree(response.getBody());
  }

  private void problem(ResponseEntity<String> response, int status, String code) throws Exception {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(json(response).path("code").asText()).isEqualTo(code);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
      })
  @Import({
    OrderStubController.class,
    OrderStubConfiguration.class,
    AuthenticatedActorResolver.class,
    HttpJsonConfiguration.class,
    OpenApiConfiguration.class,
    CommerceExceptionHandler.class
  })
  static class TestApplication {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-01-20T01:00:00.123456789Z"), ZoneOffset.UTC);
    }
  }
}
