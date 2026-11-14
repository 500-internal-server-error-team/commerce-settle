package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.payment.infrastructure.persistence.inbox.JdbcInboxStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
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
@Import({JdbcInboxStore.class, PaymentWorkWakeupIntegrationTest.Config.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentWorkWakeupIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired InboxStore inbox;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager manager;
  @Autowired Probe probe;
  @Autowired PaymentRequestProcessor processor;

  @Test
  @DisplayName("실제 Inbox 커밋은 즉시 깨우고 롤백·중복 저장은 새 신호를 만들지 않는다")
  void wakesOnlyForCommittedInsertion() throws Exception {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(probe.calls.get()).isEqualTo(1));
    probe.calls.set(0);
    probe.seen.set(0);

    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "PaymentCancellationRequested",
            1,
            OffsetDateTime.parse("2026-11-14T00:00:00Z"),
            UUID.randomUUID(),
            0,
            new PaymentCancellationRequestedPayload(
                UUID.randomUUID(), 0, UUID.randomUUID(), UUID.randomUUID()));
    var source =
        new com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source(
            "commerce-settle.commerce.v1", 0, 1, mapper.writeValueAsBytes(event));
    var tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        status -> {
          inbox.saveWaiting(event, source);
          assertThat(probe.calls.get()).isZero();
          status.setRollbackOnly();
        });
    assertThat(probe.calls.get()).isZero();
    tx.executeWithoutResult(
        status -> {
          inbox.saveWaiting(event, source);
          assertThat(probe.calls.get()).isZero();
        });
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(probe.seen.get()).isEqualTo(1));
    tx.executeWithoutResult(status -> inbox.saveWaiting(event, source));
    processor.stop();
    assertThat(probe.calls.get()).isEqualTo(1);
  }

  static class Probe {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger seen = new AtomicInteger();
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
    PaymentRequestProcessor processor(JdbcTemplate jdbc, Probe probe) {
      var service = mock(ApplyOrderPaymentRequestService.class);
      when(service.processNext())
          .thenAnswer(
              i -> {
                probe.seen.set(
                    jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Integer.class));
                probe.calls.incrementAndGet();
                return false;
              });
      return new PaymentRequestProcessor(service, 1, 1);
    }
  }
}
