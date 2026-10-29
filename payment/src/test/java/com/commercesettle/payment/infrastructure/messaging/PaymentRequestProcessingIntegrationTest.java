package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 커밋 경계에서 JDBC Inbox와 JPA 결제 저장이 함께 커밋·롤백되는지 확인한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentRequestProcessingIntegrationTest.Config.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRequestProcessingIntegrationTest {

  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID CYCLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService service;
  @Autowired private InboxStore inbox;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;

  @AfterEach
  void deleteRows() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM payment.payment_jobs");
    jdbc.update("DELETE FROM payment.payments");
    jdbc.update("DELETE FROM payment.inbox");
  }

  @Test
  @DisplayName("WAITING 결제 요청을 처리하면 결제·승인 작업·APPLIED가 함께 커밋된다")
  void commitsPaymentJobAndAppliedInboxTogether() {
    saveWaiting();

    assertThat(service.processNext()).isTrue();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID paymentId =
        jdbc.queryForObject(
            "SELECT id FROM payment.payments WHERE order_id = ?", UUID.class, ORDER_ID);
    assertThat(
            jdbc.queryForObject(
                "SELECT business_key FROM payment.payment_jobs WHERE payment_id = ?",
                UUID.class,
                paymentId))
        .isEqualTo(CYCLE_ID);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("APPLIED");
    assertThat(jdbc.queryForObject("SELECT result_ref FROM payment.inbox", UUID.class))
        .isEqualTo(paymentId);
    assertThat(service.processNext()).isFalse();
  }

  @Test
  @DisplayName("저장 중 실패하면 결제·작업·Inbox 변경이 모두 롤백되어 WAITING으로 남는다")
  void rollsBackEverythingWhenSavingFails() {
    saveWaiting();
    // 같은 회차의 승인 작업을 미리 넣어 UNIQUE(operation, business_key) 위반을 일으킨다.
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO payment.payment_jobs (id, payment_id, operation, business_key, status,"
                + " attempts, next_run_at, created_at, updated_at, version)"
                + " VALUES (?, ?, 'APPROVE', ?, 'PENDING', 0, now(), now(), now(), 0)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            CYCLE_ID);

    assertThatThrownBy(service::processNext).isInstanceOf(RuntimeException.class);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("WAITING");
    assertThat(jdbc.queryForObject("SELECT result_ref IS NULL FROM payment.inbox", Boolean.class))
        .isTrue();
  }

  private void saveWaiting() {
    byte[] body = cardPaymentEvent().getBytes(StandardCharsets.UTF_8);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(body, new TypeReference<>() {});
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status ->
                  inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body)));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String cardPaymentEvent() {
    return """
        {
          "eventId": "00000000-0000-0000-0000-0000000000a6",
          "eventType": "OrderPaymentRequested",
          "eventVersion": 1,
          "occurredAt": "2026-01-01T00:00:00Z",
          "aggregateId": "%1$s",
          "aggregateVersion": 0,
          "payload": {
            "orderId": "%1$s",
            "buyerId": "00000000-0000-0000-0000-0000000000a2",
            "sellerId": "00000000-0000-0000-0000-0000000000a3",
            "paymentCycleId": "%2$s",
            "productId": "00000000-0000-0000-0000-0000000000a5",
            "orderVersion": 0,
            "status": "PAYMENT_PENDING",
            "policyVersion": "test-v1",
            "paymentWaitUntil": "2026-01-01T00:10:00Z",
            "externalMethod": "CREDIT_CARD",
            "methodAccountRef": "test-card",
            "grossAmount": 23000,
            "couponAmount": 3000,
            "totalAmount": 20000,
            "mileageAmount": 0,
            "externalAmount": 20000,
            "sellerCouponAmount": 0,
            "platformCouponAmount": 3000,
            "settlementBaseAmount": 23000
          }
        }
        """
        .formatted(ORDER_ID, CYCLE_ID);
  }

  @TestConfiguration
  // 같은 패키지의 다른 테스트 설정을 읽지 않도록 저장소 구현만 등록한다.
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(Repository.class))
  @Import(ApplyOrderPaymentRequestService.class)
  static class Config {
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
