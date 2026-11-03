package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.service.RefundSettlementReplyService.Outcome;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 정산 응답 반영과 다음 작업·결과 이벤트가 같은 커밋에 남는지 실제 DB로 확인한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RefundSettlementReplyIntegrationTest.Config.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundSettlementReplyIntegrationTest {

  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private RefundSettlementReplyService replies;
  @Autowired private PaymentRepository payments;
  @Autowired private RefundRepository refunds;
  @Autowired private RefundSagaRepository sagas;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;

  @AfterEach
  void deleteRows() {
    JdbcTemplate jdbc = jdbc();
    jdbc.update("DELETE FROM payment.payment_jobs");
    jdbc.update("DELETE FROM payment.refund_sagas");
    jdbc.update("DELETE FROM payment.refunds");
    jdbc.update("DELETE FROM payment.payments");
    jdbc.update("DELETE FROM payment.outbox");
  }

  @Test
  @DisplayName("예약 성공을 반영하면 외부 환불 작업을 하나만 만들고 같은 결과의 재전달은 무변경이다")
  void createsRefundJobOnceReserved() {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000));
    Refund refund = accepted(payment);
    UUID reservationId = UUID.randomUUID();
    var reserved =
        new RefundReservationResultPayload(
            refund.getId(),
            payment.getId(),
            RefundReservationResultPayload.Result.RESERVED,
            reservationId,
            null);

    assertThat(replies.applyReservationResult(reserved)).isEqualTo(Outcome.APPLIED);
    assertThat(replies.applyReservationResult(reserved)).isEqualTo(Outcome.ALREADY_APPLIED);

    assertThat(jdbc().queryForMap("SELECT status FROM payment.refunds"))
        .containsEntry("status", "EXTERNAL_PENDING");
    assertThat(jdbc().queryForMap("SELECT step, reservation_ref FROM payment.refund_sagas"))
        .containsEntry("step", "EXTERNAL_REFUND_PENDING")
        .containsEntry("reservation_ref", reservationId);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT operation, refund_id, business_key, status FROM payment.payment_jobs"))
        .singleElement()
        .isEqualTo(
            Map.of(
                "operation",
                "REFUND",
                "refund_id",
                refund.getId(),
                "business_key",
                refund.getBusinessKey(),
                "status",
                "PENDING"));
  }

  @Test
  @DisplayName("정산금 예약이 거절되면 외부 환불 없이 실패하고 RefundFailed를 한 번만 남긴다")
  void failsWithoutExternalRefundWhenReservationRejected() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    Refund refund = accepted(payment);
    var rejected =
        new RefundReservationResultPayload(
            refund.getId(),
            payment.getId(),
            RefundReservationResultPayload.Result.REJECTED,
            null,
            "SETTLEMENT_PAYOUT_RESERVED");

    assertThat(replies.applyReservationResult(rejected)).isEqualTo(Outcome.APPLIED);
    assertThat(replies.applyReservationResult(rejected)).isEqualTo(Outcome.ALREADY_APPLIED);

    assertThat(jdbc().queryForObject("SELECT status FROM payment.refunds", String.class))
        .isEqualTo("DECLINED");
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isZero();
    JsonNode failed = outboxPayload("RefundFailed");
    assertThat(failed.path("reasonCode").asText()).isEqualTo("SETTLEMENT_PAYOUT_RESERVED");
    assertThat(failed.path("reservationReleased").asBoolean()).isTrue();
    assertThat(failed.path("orderId").asText()).isEqualTo(payment.getOrderId().toString());
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.outbox", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("원장 상쇄 확인 뒤 RefundCompleted를 남기고 금융 환불은 주문 복구 확인을 기다린다")
  void requestsOrderRestoreAfterLedgerReversed() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    Refund refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    RefundSaga saga = RefundSaga.start(refund, NOW);
    refund.reserved(NOW);
    refund.externalSucceeded(UUID.randomUUID(), NOW);
    refund.mileageRestored(NOW);
    UUID reservationId = UUID.randomUUID();
    saga.reserved(reservationId, NOW);
    saga.externalSettled(NOW);
    saga.mileageRestored(NOW);
    refunds.save(refund);
    sagas.save(saga);
    var reversed =
        new RefundLedgerReversedPayload(
            refund.getId(), payment.getId(), reservationId, NOW.atOffset(ZoneOffset.UTC));

    assertThat(replies.applyLedgerReversed(reversed)).isEqualTo(Outcome.APPLIED);
    assertThat(replies.applyLedgerReversed(reversed)).isEqualTo(Outcome.ALREADY_APPLIED);

    assertThat(jdbc().queryForObject("SELECT status FROM payment.refunds", String.class))
        .isEqualTo("INTERNAL_PENDING");
    assertThat(jdbc().queryForMap("SELECT step, ledger_reversed_at FROM payment.refund_sagas"))
        .containsEntry("step", "ORDER_RESTORE_PENDING")
        .extractingByKey("ledger_reversed_at")
        .isNotNull();
    JsonNode completed = outboxPayload("RefundCompleted");
    assertThat(completed.path("refundId").asText()).isEqualTo(refund.getId().toString());
    assertThat(completed.path("orderId").asText()).isEqualTo(payment.getOrderId().toString());
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.outbox", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("다른 예약의 원장 상쇄 응답은 반영하지 않는다")
  void rejectsLedgerReversalOfOtherReservation() {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    Refund refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    RefundSaga saga = RefundSaga.start(refund, NOW);
    refund.reserved(NOW);
    refund.externalSucceeded(UUID.randomUUID(), NOW);
    refund.mileageRestored(NOW);
    saga.reserved(UUID.randomUUID(), NOW);
    saga.externalSettled(NOW);
    saga.mileageRestored(NOW);
    refunds.save(refund);
    sagas.save(saga);

    Outcome outcome =
        replies.applyLedgerReversed(
            new RefundLedgerReversedPayload(
                refund.getId(), payment.getId(), UUID.randomUUID(), NOW.atOffset(ZoneOffset.UTC)));

    assertThat(outcome).isEqualTo(Outcome.REJECTED);
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("LEDGER_REVERSAL_PENDING");
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
  }

  @Test
  @DisplayName("외부 확정 거절 뒤 예약 해제가 확인되면 환불을 실패로 끝내고 RefundFailed를 남긴다")
  void failsAfterReservationReleased() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0));
    Refund refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    RefundSaga saga = RefundSaga.start(refund, NOW);
    refund.reserved(NOW);
    refund.externalDeclined(NOW);
    UUID reservationId = UUID.randomUUID();
    saga.reserved(reservationId, NOW);
    saga.externalDeclined("REFUND_EXTERNAL_DECLINED", NOW);
    refunds.save(refund);
    sagas.save(saga);
    var released =
        new RefundReservationReleasedPayload(refund.getId(), payment.getId(), reservationId);

    assertThat(replies.applyReservationReleased(released)).isEqualTo(Outcome.APPLIED);
    assertThat(replies.applyReservationReleased(released)).isEqualTo(Outcome.ALREADY_APPLIED);

    assertThat(jdbc().queryForObject("SELECT status FROM payment.refunds", String.class))
        .isEqualTo("DECLINED");
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("FAILED");
    assertThat(outboxPayload("RefundFailed").path("reasonCode").asText())
        .isEqualTo("REFUND_EXTERNAL_DECLINED");
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

  private Refund accepted(Payment payment) {
    Refund refund = refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW));
    sagas.save(RefundSaga.start(refund, NOW));
    return refund;
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
  @Import({RefundSettlementReplyService.class, RefundEvents.class})
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
