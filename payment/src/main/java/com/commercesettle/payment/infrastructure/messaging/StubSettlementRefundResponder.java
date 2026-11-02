package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.service.RefundSettlementReplyService;
import com.commercesettle.payment.application.service.RefundSettlementReplyService.AwaitingSettlement;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>Settlement 환불 응답 stub.</b> Settlement의 환불 예약·원장 상쇄·예약 해제(#154) 연동 전까지 정산 응답을 기다리는 SAGA에 즉시 성공
 * 응답을 넣는다. 예약은 항상 성공하고 예약 ID는 환불 ID에서 고정한다. 실제 정산 원장·예약은 만들지 않는다.
 *
 * <p>교체 지점: Settlement 토픽의 RefundReservationResult·RefundLedgerReversed·RefundReservationReleased
 * 수신기가 {@link RefundSettlementReplyService}의 같은 메서드를 호출하게 하고 {@code
 * app.refund.settlement-stub.enabled=false}로 끈다. Payment가 발행하는 요청 이벤트(RefundReservationRequested
 * 등)는 stub 여부와 관계없이 Outbox에 남는다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "worker")
@ConditionalOnProperty(name = "app.refund.enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.refund.settlement-stub.enabled", havingValue = "true")
public class StubSettlementRefundResponder {
  private static final Logger LOG = LoggerFactory.getLogger(StubSettlementRefundResponder.class);

  private final RefundSettlementReplyService replies;
  private final Clock clock;
  private final int batchSize;

  public StubSettlementRefundResponder(
      RefundSettlementReplyService replies,
      Clock clock,
      @Value("${app.refund.settlement-stub.batch-size:20}") int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Invalid settlement stub batch size");
    }
    this.replies = replies;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.refund.poll-interval-ms:1000}",
      fixedDelayString = "${app.refund.poll-interval-ms:1000}")
  public void run() {
    for (AwaitingSettlement awaiting : replies.awaitingSettlement(batchSize)) {
      try {
        respond(awaiting);
      } catch (RuntimeException e) {
        LOG.atError()
            .setCause(e)
            .addKeyValue("event", "payment.refund.settlement-stub")
            .addKeyValue("refundId", awaiting.refundId())
            .addKeyValue("errorType", e.getClass().getName())
            .log("Stub settlement reply failed; it will be retried on the next poll");
      }
    }
  }

  private void respond(AwaitingSettlement awaiting) {
    switch (awaiting.step()) {
      case RESERVATION_REQUESTED ->
          replies.applyReservationResult(
              new RefundReservationResultPayload(
                  awaiting.refundId(),
                  awaiting.paymentId(),
                  RefundReservationResultPayload.Result.RESERVED,
                  reservationId(awaiting.refundId()),
                  null));
      case LEDGER_REVERSAL_PENDING ->
          replies.applyLedgerReversed(
              new RefundLedgerReversedPayload(
                  awaiting.refundId(),
                  awaiting.paymentId(),
                  awaiting.reservationId(),
                  clock.instant().atOffset(ZoneOffset.UTC)));
      case RELEASE_PENDING ->
          replies.applyReservationReleased(
              new RefundReservationReleasedPayload(
                  awaiting.refundId(), awaiting.paymentId(), awaiting.reservationId()));
      default -> {
        // 정산 응답을 기다리지 않는 단계는 조회되지 않는다.
      }
    }
  }

  static UUID reservationId(UUID refundId) {
    return UUID.nameUUIDFromBytes(
        ("stub-settlement-reservation:" + refundId).getBytes(StandardCharsets.UTF_8));
  }
}
