package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Inbox 선점·환불 접수·Outbox가 실제 커밋 경계에서 함께 저장되는지 확인한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RefundAcceptanceIntegrationTest.Config.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundAcceptanceIntegrationTest {

  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService service;
  @Autowired private InboxStore inbox;
  @Autowired private PaymentRepository payments;
  @Autowired private RefundRepository refunds;
  @Autowired private RefundSagaRepository sagas;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;

  @AfterEach
  void deleteRows() {
    JdbcTemplate jdbc = jdbc();
    jdbc.update("DELETE FROM payment.refund_sagas");
    jdbc.update("DELETE FROM payment.refunds");
    jdbc.update("DELETE FROM payment.payments");
    jdbc.update("DELETE FROM payment.inbox");
    jdbc.update("DELETE FROM payment.outbox");
  }

  @Test
  @DisplayName("환불 승인을 처리하면 환불·SAGA·RefundAccepted·정산금 예약 요청·APPLIED가 함께 커밋된다")
  void acceptsRefundWithReservationRequest() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000));
    UUID requestId = UUID.randomUUID();
    saveWaiting(refundApproved(UUID.randomUUID(), requestId, payment));

    assertThat(service.processNext()).isTrue();

    Map<String, Object> refund = jdbc().queryForMap("SELECT * FROM payment.refunds");
    assertThat(refund)
        .containsEntry("payment_id", payment.getId())
        .containsEntry("order_refund_request_id", requestId)
        .containsEntry("external_amount", 8_000L)
        .containsEntry("mileage_amount", 2_000L)
        .containsEntry("status", "RESERVING")
        .containsEntry("external_status", "PENDING");
    UUID refundId = (UUID) refund.get("id");
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("RESERVATION_REQUESTED");
    assertThat(jdbc().queryForMap("SELECT status, result_ref FROM payment.inbox"))
        .containsEntry("status", "APPLIED")
        .containsEntry("result_ref", refundId);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT event_type FROM payment.outbox ORDER BY sequence_no", String.class))
        .containsExactly("RefundAccepted", "RefundReservationRequested");
    JsonNode reservation = outboxPayload("RefundReservationRequested");
    assertThat(reservation.path("refundId").asText()).isEqualTo(refundId.toString());
    assertThat(reservation.path("netAmount").asLong()).isEqualTo(8_500);
    JsonNode accepted = outboxPayload("RefundAccepted");
    assertThat(accepted.path("requestId").asText()).isEqualTo(requestId.toString());
    assertThat(accepted.path("orderId").asText()).isEqualTo(payment.getOrderId().toString());
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT DISTINCT message_key FROM payment.outbox WHERE aggregate_id = ?",
                    String.class,
                    payment.getId()))
        .containsExactly(payment.getId().toString());
  }

  @Test
  @DisplayName("같은 취소·반품 요청이 다른 이벤트로 다시 와도 환불·이벤트를 더 만들지 않는다")
  void linksRedeliveredRequestToSameRefund() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    UUID requestId = UUID.randomUUID();
    saveWaiting(refundApproved(UUID.randomUUID(), requestId, payment));
    service.processNext();
    saveWaiting(refundApproved(UUID.randomUUID(), requestId, payment));

    assertThat(service.processNext()).isTrue();

    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.refunds", Long.class))
        .isEqualTo(1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.outbox", Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT DISTINCT result_ref FROM payment.inbox WHERE status = 'APPLIED'",
                    UUID.class))
        .hasSize(1);
  }

  @Test
  @DisplayName("같은 원결제에 다른 요청으로 환불이 오면 중복 환불로 확인 대상에 남긴다")
  void rejectsSecondRefundForSamePayment() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    saveWaiting(refundApproved(UUID.randomUUID(), UUID.randomUUID(), payment));
    service.processNext();
    UUID second = UUID.randomUUID();
    saveWaiting(refundApproved(second, UUID.randomUUID(), payment));

    assertThat(service.processNext()).isTrue();

    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT status, last_error_code FROM payment.inbox WHERE event_id = ?", second))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", RefundEventHandler.ALREADY_IN_PROGRESS);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.refunds", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("원결제가 아직 확정되지 않았으면 환불을 만들지 않고 다시 처리할 대기로 남긴다")
  void defersRefundUntilPaymentConfirmed() throws Exception {
    Payment payment =
        Payment.create(
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PaymentAmounts(10_000, 0, 0, 0),
            "v1",
            NOW);
    payment.startProcessing(NOW);
    payment = payments.save(payment);
    saveWaiting(refundApproved(UUID.randomUUID(), UUID.randomUUID(), payment));

    assertThat(service.processNext()).isTrue();

    assertThat(jdbc().queryForMap("SELECT status, last_error_code, attempts FROM payment.inbox"))
        .containsEntry("status", "WAITING")
        .containsEntry("last_error_code", RefundEventHandler.PAYMENT_NOT_CONFIRMED)
        .containsEntry("attempts", 1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.refunds", Long.class)).isZero();
  }

  @Test
  @DisplayName("주문 복구 확인을 반영해야 환불이 완료되고 재전달은 무변경으로 처리한다")
  void completesRefundAfterOrderRestored() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    Refund refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    RefundSaga saga = RefundSaga.start(refund, NOW);
    refund.reserved(NOW);
    refund.externalSucceeded(UUID.randomUUID(), NOW);
    refund.mileageRestored(NOW);
    saga.reserved(UUID.randomUUID(), NOW);
    saga.externalSettled(NOW);
    saga.mileageRestored(NOW);
    saga.ledgerReversed(NOW, NOW);
    refunds.save(refund);
    sagas.save(saga);
    saveWaiting(orderRestored(UUID.randomUUID(), refund, payment));
    saveWaiting(orderRestored(UUID.randomUUID(), refund, payment));

    assertThat(service.processNext()).isTrue();
    assertThat(service.processNext()).isTrue();

    assertThat(jdbc().queryForObject("SELECT status FROM payment.refunds", String.class))
        .isEqualTo("COMPLETED");
    assertThat(jdbc().queryForMap("SELECT step, order_restored_at FROM payment.refund_sagas"))
        .containsEntry("step", "COMPLETED")
        .extractingByKey("order_restored_at")
        .isNotNull();
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT status FROM payment.inbox WHERE event_type = 'RefundOrderRestored'",
                    String.class))
        .containsExactly("APPLIED", "APPLIED");
  }

  @Test
  @DisplayName("RefundCompleted를 보내기 전에 온 주문 복구 확인으로는 완료하지 않는다")
  void rejectsOrderRestoredBeforeRefundCompleted() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    Refund refund = refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW));
    sagas.save(RefundSaga.start(refund, NOW));
    saveWaiting(orderRestored(UUID.randomUUID(), refund, payment));

    assertThat(service.processNext()).isTrue();

    assertThat(jdbc().queryForMap("SELECT status, last_error_code FROM payment.inbox"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", RefundEventHandler.ORDER_RESTORED_UNEXPECTED);
    assertThat(jdbc().queryForObject("SELECT status FROM payment.refunds", String.class))
        .isEqualTo("RESERVING");
  }

  private Payment confirmed(PaymentAmounts amounts) {
    Payment payment =
        Payment.create(
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            amounts,
            "v1",
            NOW);
    if (amounts.externalAmount() > 0) {
      payment.startProcessing(NOW);
    }
    payment.confirm(NOW);
    return payments.save(payment);
  }

  private static EventEnvelope<RefundApprovedPayload> refundApproved(
      UUID eventId, UUID requestId, Payment payment) {
    OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
    return new EventEnvelope<>(
        eventId,
        "RefundApproved",
        1,
        at,
        payment.getOrderId(),
        3,
        new RefundApprovedPayload(requestId, payment.getOrderId(), payment.getId(), at, at, 3));
  }

  private static EventEnvelope<RefundOrderRestoredPayload> orderRestored(
      UUID eventId, Refund refund, Payment payment) {
    return new EventEnvelope<>(
        eventId,
        "RefundOrderRestored",
        1,
        NOW.atOffset(ZoneOffset.UTC),
        payment.getOrderId(),
        5,
        new RefundOrderRestoredPayload(refund.getId(), payment.getId(), payment.getOrderId(), 5));
  }

  private void saveWaiting(EventEnvelope<?> event) throws Exception {
    byte[] body = mapper.writeValueAsBytes(event);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status ->
                inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body)));
  }

  private JsonNode outboxPayload(String eventType) throws Exception {
    String json =
        jdbc()
            .queryForObject(
                "SELECT payload::text FROM payment.outbox WHERE event_type = ?",
                String.class,
                eventType);
    return mapper.readTree(json).path("payload");
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  @TestConfiguration
  // 같은 패키지의 다른 테스트 설정을 읽지 않도록 저장소 구현만 등록한다.
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(Repository.class))
  @Import({
    ApplyOrderPaymentRequestService.class,
    PaymentNotices.class,
    RefundEventHandler.class,
    RefundEvents.class
  })
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

    @Bean
    TransactionTemplate transactions(PlatformTransactionManager manager) {
      return new TransactionTemplate(manager);
    }
  }
}
