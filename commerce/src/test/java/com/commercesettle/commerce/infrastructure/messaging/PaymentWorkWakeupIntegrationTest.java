package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox;
import com.commercesettle.commerce.application.service.OrderResultService;
import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.commercesettle.commerce.infrastructure.persistence.inbox.JdbcPaymentAcceptanceInbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JdbcPaymentAcceptanceInbox.class, PaymentWorkWakeupIntegrationTest.Config.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentWorkWakeupIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired PaymentAcceptanceInbox inbox;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager manager;
  @Autowired Probe probe;
  @Autowired PaymentAcceptedProcessor accepted;
  @Autowired OrderResultProcessor results;

  @Test
  @DisplayName("커밋한 접수와 결과를 각각 깨우며 롤백한 Inbox는 반영하지 않는다")
  void wakesEachCommittedInboxKind() throws Exception {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(probe.accepted.get()).isEqualTo(1);
              assertThat(probe.results.get()).isEqualTo(1);
            });
    probe.accepted.set(0);
    probe.results.set(0);
    probe.visible.set(0);

    var time = OffsetDateTime.parse("2026-11-14T00:00:00Z");
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "PaymentAccepted",
            1,
            time,
            UUID.randomUUID(),
            0,
            new PaymentAcceptedPayload(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID()));
    EventEnvelope<com.fasterxml.jackson.databind.JsonNode> result =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "PaymentConfirmed",
            1,
            time,
            UUID.randomUUID(),
            1,
            mapper.createObjectNode().put("orderId", UUID.randomUUID().toString()));
    var source =
        new PaymentAcceptanceInbox.Source(
            "commerce-settle.payment.v1", 0, 1, mapper.writeValueAsBytes(event));
    var resultSource =
        new PaymentAcceptanceInbox.Source(
            "commerce-settle.payment.v1", 0, 2, mapper.writeValueAsBytes(result));
    var tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        status -> {
          inbox.saveWaiting(event, source, Instant.now());
          status.setRollbackOnly();
        });
    assertThat(probe.accepted.get()).isZero();
    tx.executeWithoutResult(
        status -> {
          inbox.saveWaiting(event, source, Instant.now());
          assertThat(probe.accepted.get()).isZero();
        });
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(probe.accepted.get()).isEqualTo(1));
    assertThat(probe.results.get()).isZero();
    tx.executeWithoutResult(
        status ->
            inbox.saveResult(
                new EventEnvelope<>(
                    result.eventId(),
                    result.eventType(),
                    result.eventVersion(),
                    result.occurredAt(),
                    result.aggregateId(),
                    result.aggregateVersion(),
                    (com.fasterxml.jackson.databind.JsonNode) result.payload()),
                resultSource,
                Instant.now()));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(probe.results.get()).isEqualTo(1));
    assertThat(probe.visible.get()).isEqualTo(2);
    accepted.stop();
    results.stop();
  }

  static class Probe {
    final AtomicInteger accepted = new AtomicInteger();
    final AtomicInteger results = new AtomicInteger();
    final AtomicInteger visible = new AtomicInteger();
  }

  @TestConfiguration
  static class Config {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Probe probe() {
      return new Probe();
    }

    @Bean
    PaymentAcceptedProcessor accepted(JdbcTemplate jdbc, Probe probe) {
      var service = mock(PaymentAcceptedService.class);
      when(service.processNext())
          .thenAnswer(
              i -> {
                probe.visible.set(
                    jdbc.queryForObject("SELECT count(*) FROM commerce.inbox", Integer.class));
                probe.accepted.incrementAndGet();
                return false;
              });
      return new PaymentAcceptedProcessor(service, 1, 1);
    }

    @Bean
    OrderResultProcessor results(JdbcTemplate jdbc, Probe probe) {
      var service = mock(OrderResultService.class);
      when(service.processNext())
          .thenAnswer(
              i -> {
                probe.visible.set(
                    jdbc.queryForObject("SELECT count(*) FROM commerce.inbox", Integer.class));
                probe.results.incrementAndGet();
                return false;
              });
      return new OrderResultProcessor(service, 1, 1);
    }
  }
}
