package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=admission-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=admission-test-delivery-key-32bytes",
      "app.address.encryption-key=admission-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=" + OrderAdmissionIntegrationTest.CREDENTIAL,
      "app.order-admission.prometheus-url=http://127.0.0.1:1"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderAdmissionIntegrationTest {
  static final String CREDENTIAL = "test-gateway-credential-0123456789abcdef0123";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService catalog;

  @Test
  @DisplayName("필수 관측이 없으면 신규 주문은 부작용 없이 503과 재시도 시간을 반환한다")
  void rejectsNewOrderWithoutObservations() {
    UUID buyer = buyer();
    UUID address =
        addresses
            .register(
                buyer,
                key(),
                new ShippingAddress("시험", "000-0000-0000", "00001", "가상시 시험로 1", "101"),
                true)
            .id();
    UUID seller = UUID.randomUUID();
    UUID product =
        catalog.register(seller, key(), "접수 제한 시험", 10000, ProductStatus.ON_SALE).getId();
    catalog.adjustAvailable(seller, product, key(), 1, 0, "시험 입고");
    var body = mapper.createObjectNode();
    body.put("productId", product.toString()).put("quantity", 1).put("mileageAmount", 0);
    body.put("addressId", address.toString()).put("expectedProductVersion", 0);
    body.put("expectedTotalAmount", 10000).put("externalMethod", "CREDIT_CARD");
    body.put("methodAccountRef", "test-card");
    body.putNull("eventId").putNull("buyerCouponId").putNull("expectedEventVersion");
    var headers = headers(buyer);
    headers.set("Idempotency-Key", key());

    var response =
        http.exchange(
            "/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_CAPACITY_EXCEEDED");
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    assertThat(jdbc.queryForObject("select count(*) from commerce.orders", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select reserved_qty from commerce.product_inventory where product_id=?",
                Long.class,
                product))
        .isZero();
  }

  @Test
  @DisplayName("필수 관측이 없어도 기존 주문 목록 조회는 계속 제공한다")
  void keepsReadAvailableWithoutObservations() {
    var response =
        http.exchange(
            "/api/orders", HttpMethod.GET, new HttpEntity<>(headers(buyer())), JsonNode.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  private UUID buyer() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','구매자','ACTIVE',now(),now(),0)",
        id,
        id + "@example.com");
    return id;
  }

  private static HttpHeaders headers(UUID buyer) {
    var result = new HttpHeaders();
    result.setContentType(MediaType.APPLICATION_JSON);
    result.setBearerAuth(CREDENTIAL);
    result.set("X-Authenticated-Actor-Kind", "BUYER");
    result.set("X-Authenticated-Actor-Id", buyer.toString());
    return result;
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
