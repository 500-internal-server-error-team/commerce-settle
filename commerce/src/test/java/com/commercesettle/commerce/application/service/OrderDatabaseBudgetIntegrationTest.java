package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      // 검증 시간을 줄이는 시험 전용 값. 운영 30초의 적정성을 이 값으로 판단하지 않는다.
      "spring.datasource.hikari.connection-timeout=500",
      "spring.datasource.hikari.maximum-pool-size=2",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=budget-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=budget-test-delivery-key-32bytes-long",
      "app.address.encryption-key=budget-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=" + OrderDatabaseBudgetIntegrationTest.GATEWAY
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OrderDatabaseBudgetIntegrationTest.Faults.class)
class OrderDatabaseBudgetIntegrationTest {
  static final String GATEWAY = "test-budget-gateway-credential-0123456789abcdef";
  private static final ThreadLocal<CommitFailure> COMMIT_FAILURE = new ThreadLocal<>();

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @MockitoBean OrderAdmissionService admission;
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource source;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService products;

  @Test
  @DisplayName("연결 포화는 주문을 부분 저장하지 않고 반환 후 같은 키와 공유 조회가 재개된다")
  void saturationLeavesNoPartialOrderAndReturnsConnections() throws Exception {
    var f = fixture();
    String key = UUID.randomUUID().toString();
    var held = new ArrayList<Connection>();
    try {
      held.add(source.getConnection());
      held.add(source.getConnection());
      var response = request(f, key, null);
      assertThat(response.getStatusCode().value()).isEqualTo(503);
      assertThat(response.getBody().path("code").asText())
          .isEqualTo("COMMON_TEMPORARILY_UNAVAILABLE");
    } finally {
      for (var connection : held) connection.close();
    }
    assertState(f, 0);
    var first = request(f, key, null);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    var replay = request(f, key, null);
    assertThat(replay.getStatusCode().value()).isEqualTo(201);
    assertThat(replay.getBody().path("id")).isEqualTo(first.getBody().path("id"));
    assertState(f, 1);

    // 같은 DataSource를 쓰는 후속·관측 조회가 연결 반환 후 재개되는지 확인한다.
    // 실제 Relay/Inbox의 15분 진행 공정성은 별도 전용 실험에서 확인한다.
    try (var workers = Executors.newFixedThreadPool(3)) {
      var futures =
          java.util.List.of("outbox", "inbox", "orders").stream()
              .map(table -> workers.submit(() -> count("select count(*) from commerce." + table)))
              .toList();
      for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isNotNegative();
    }
    assertThat(source.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections())
        .isZero();
  }

  @ParameterizedTest
  @EnumSource(CommitFailure.class)
  @DisplayName("커밋 전후 응답 미확정은 같은 키로 확인하며 예약·Outbox를 중복 저장하지 않는다")
  void uncertainCommitIsResolvedByTheSameKey(CommitFailure failure) throws Exception {
    var f = fixture();
    String key = UUID.randomUUID().toString();
    var response = request(f, key, failure);
    assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    assertState(f, failure == CommitFailure.AFTER ? 1 : 0);
    var first = request(f, key, null);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(first.getBody().path("status").asText()).isEqualTo("PAYMENT_PENDING");
    var replay = request(f, key, null);
    assertThat(replay.getBody().path("id")).isEqualTo(first.getBody().path("id"));
    assertState(f, 1);
    assertThat(source.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections())
        .isZero();
  }

  @Test
  @DisplayName("주문·Outbox 저장 뒤 재고 잠금 대기가 초과해도 같은 키로 전체 주문을 다시 접수한다")
  void stockLockTimeoutRollsBackFlushedOrderAndOutbox() throws Exception {
    var f = fixture();
    String key = UUID.randomUUID().toString();
    try (var holder = source.getConnection()) {
      holder.setAutoCommit(false);
      try (var lock =
          holder.prepareStatement(
              "select id from commerce.product_inventory where product_id=? for update")) {
        lock.setObject(1, f.product());
        lock.executeQuery().close();
      }
      var response = request(f, key, null);
      assertThat(response.getStatusCode().value()).isEqualTo(503);
      assertThat(response.getBody().path("code").asText())
          .isEqualTo("COMMON_TEMPORARILY_UNAVAILABLE");
      holder.rollback();
    }
    assertState(f, 0);
    var response = request(f, key, null);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(request(f, key, null).getBody().path("id")).isEqualTo(response.getBody().path("id"));
    assertState(f, 1);
  }

  private void assertState(Fixture f, long orders) {
    assertThat(count("select count(*) from commerce.orders where product_id=?", f.product()))
        .isEqualTo(orders);
    assertThat(
            count(
                "select reserved_qty from commerce.product_inventory where product_id=?",
                f.product()))
        .isEqualTo(orders);
    assertThat(
            count(
                "select available_qty from commerce.product_inventory where product_id=?",
                f.product()))
        .isEqualTo(2 - orders);
    assertThat(
            count(
                "select count(*) from commerce.inventory_reservations where product_id=?",
                f.product()))
        .isEqualTo(orders);
    assertThat(
            count(
                "select count(*) from commerce.outbox where payload->'payload'->>'productId'=? and event_type='OrderPaymentRequested'",
                f.product().toString()))
        .isEqualTo(orders);
    assertThat(
            count(
                "select count(*) from commerce.idempotency_records where actor_kind='BUYER' and actor_id=? and operation='ORDER_CREATE'",
                f.buyer().toString()))
        .isEqualTo(orders);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private Fixture fixture() {
    UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','buyer','ACTIVE',now(),now(),0)",
        buyer,
        buyer + "@example.com");
    jdbc.update(
        "insert into commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version) values (?,?,'hash','seller','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    UUID address =
        addresses
            .register(
                buyer,
                UUID.randomUUID().toString(),
                new ShippingAddress("시험", "000", "00001", "시험로", ""),
                true)
            .id();
    var product =
        products.register(
            seller, UUID.randomUUID().toString(), "예산 시험", 10000, ProductStatus.ON_SALE);
    products.adjustAvailable(seller, product.getId(), UUID.randomUUID().toString(), 2, 0, "시험 입고");
    return new Fixture(buyer, address, product.getId());
  }

  private ResponseEntity<JsonNode> request(Fixture f, String key, CommitFailure failure) {
    ObjectNode body =
        mapper
            .createObjectNode()
            .put("productId", f.product().toString())
            .put("quantity", 1)
            .putNull("eventId")
            .putNull("buyerCouponId")
            .put("mileageAmount", 0)
            .put("addressId", f.address().toString())
            .put("expectedProductVersion", 0)
            .putNull("expectedEventVersion")
            .put("expectedTotalAmount", 10000)
            .put("externalMethod", "CREDIT_CARD")
            .put("methodAccountRef", "test-card");
    var headers = new HttpHeaders();
    headers.setBearerAuth(GATEWAY);
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.set("X-Authenticated-Actor-Kind", "BUYER");
    headers.set("X-Authenticated-Actor-Id", f.buyer().toString());
    headers.set("Idempotency-Key", key);
    if (failure != null) headers.set("X-Test-Commit-Failure", failure.name());
    return http.exchange(
        "/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
  }

  record Fixture(UUID buyer, UUID address, UUID product) {}

  enum CommitFailure {
    BEFORE,
    AFTER
  }

  @TestConfiguration
  static class Faults {
    @Bean
    Filter commitFailureScope() {
      return (request, response, chain) -> {
        String failure = ((HttpServletRequest) request).getHeader("X-Test-Commit-Failure");
        if (failure != null) COMMIT_FAILURE.set(CommitFailure.valueOf(failure));
        try {
          chain.doFilter(request, response);
        } finally {
          COMMIT_FAILURE.remove();
        }
      };
    }

    @Bean
    static BeanPostProcessor commitFaultDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof HikariDataSource pool)) return bean;
          return new DelegatingDataSource(pool) {
            public void close() {
              pool.close();
            }

            @Override
            public Connection getConnection() throws SQLException {
              var actual = super.getConnection();
              boolean[] orderWrite = {false};
              return (Connection)
                  Proxy.newProxyInstance(
                      Connection.class.getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")
                            && args != null
                            && args[0] instanceof String sql
                            && sql.toLowerCase(java.util.Locale.ROOT)
                                .matches("(?s).*insert into (commerce\\.)?orders[ (].*"))
                          orderWrite[0] = true;
                        CommitFailure fault = null;
                        if (method.getName().equals("commit") && orderWrite[0]) {
                          fault = COMMIT_FAILURE.get();
                          COMMIT_FAILURE.remove();
                          if (fault == CommitFailure.BEFORE)
                            throw new SQLException("Injected pre-commit connection loss", "08006");
                        }
                        try {
                          Object result = method.invoke(actual, args);
                          if (fault == CommitFailure.AFTER)
                            throw new SQLException(
                                "Injected post-commit acknowledgement loss", "08006");
                          return result;
                        } catch (InvocationTargetException e) {
                          throw e.getCause();
                        }
                      });
            }
          };
        }
      };
    }
  }
}
