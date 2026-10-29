package com.commercesettle.commerce.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.service.OrderStubService;
import com.commercesettle.commerce.infrastructure.config.HttpJsonConfiguration;
import com.commercesettle.commerce.presentation.exception.CommerceExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
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
      "management.tracing.enabled=false"
    })
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

  @Test
  void createsAndReplaysThenReadsSameOwnedSnapshotThroughRealHttp() throws Exception {
    String key = UUID.randomUUID().toString();
    var created = call(HttpMethod.POST, "/api/orders", BUYER, "BUYER", key, BODY);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    JsonNode order = json(created);
    assertThat(order.size()).isEqualTo(28);
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
    for (JsonNode order : page.path("items")) {
      assertThat(order.path("buyerId").asText()).isEqualTo(OTHER);
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
    var api = http.getForEntity("/v3/api-docs", String.class);
    assertThat(api.getStatusCode().value()).isEqualTo(200);
    var properties =
        json(api).path("components").path("schemas").path("OrderStubCreate").path("properties");
    assertThat(properties.size()).isEqualTo(7);
    assertThat(properties.has("addressId")).isFalse();
    assertThat(properties.has("eventId")).isFalse();
    assertThat(properties.has("buyerCouponId")).isFalse();
  }

  private ResponseEntity<String> call(
      HttpMethod method, String path, String actor, String kind, String key, String body) {
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
    return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
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
    OrderStubService.class,
    AuthenticatedActorResolver.class,
    HttpJsonConfiguration.class,
    CommerceExceptionHandler.class
  })
  static class TestApplication {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-01-20T01:00:00.123456789Z"), ZoneOffset.UTC);
    }
  }
}
