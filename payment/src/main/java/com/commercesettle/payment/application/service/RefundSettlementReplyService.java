package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.RefundCompletedPayload;
import com.commercesettle.payment.application.event.RefundFailedPayload;
import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement의 환불 응답(정산금 예약 결과·원장 상쇄·예약 해제)을 환불 SAGA에 반영한다. 응답 한 건을 한 트랜잭션에서 환불 행을 잠그고 반영하며, 다음 단계에
 * 필요한 작업이나 결과 이벤트를 같은 트랜잭션에 저장한다.
 *
 * <p>Settlement 토픽의 응답은 Inbox를 거쳐 {@link RefundEventHandler}가 이 메서드들을 호출한다. 같은 결과의 재전달은 {@link
 * Outcome#ALREADY_APPLIED}로 무변경 처리하고, 기다리지 않는 단계·다른 원결제·다른 예약의 응답은 {@link Outcome#REJECTED}로 반영하지
 * 않는다.
 */
@Service
public class RefundSettlementReplyService {
  static final String RESERVATION_REJECTED = "REFUND_RESERVATION_REJECTED";

  private static final Logger LOG = LoggerFactory.getLogger(RefundSettlementReplyService.class);

  private final RefundRepository refunds;
  private final RefundSagaRepository sagas;
  private final PaymentRepository payments;
  private final PaymentJobRepository jobs;
  private final RefundEvents events;
  private final Clock clock;

  RefundSettlementReplyService(
      RefundRepository refunds,
      RefundSagaRepository sagas,
      PaymentRepository payments,
      PaymentJobRepository jobs,
      RefundEvents events,
      Clock clock) {
    this.refunds = refunds;
    this.sagas = sagas;
    this.payments = payments;
    this.jobs = jobs;
    this.events = events;
    this.clock = clock;
  }

  public enum Outcome {
    APPLIED,
    ALREADY_APPLIED,
    /** 예약 결과가 WAITING이라 계속 기다린다. */
    WAITING,
    REJECTED
  }

  /** 정산금 예약 결과. RESERVED면 외부 환불·마일리지 복원 작업을 만들고, REJECTED면 외부 환불 없이 실패로 끝내 RefundFailed를 저장한다. */
  @Transactional
  public Outcome applyReservationResult(RefundReservationResultPayload reply) {
    Optional<Locked> found = lock(reply.refundId(), reply.paymentId());
    if (found.isEmpty()) {
      return rejected(reply.refundId(), "RefundReservationResult");
    }
    Refund refund = found.get().refund();
    RefundSaga saga = found.get().saga();
    Instant now = clock.instant();
    if (saga.getStep() != RefundSagaStep.RESERVATION_REQUESTED) {
      // 결과를 반영한 뒤 늦게 온 대기 응답과 같은 결과의 재전달은 무변경이다.
      boolean same =
          switch (reply.result()) {
            case WAITING -> true;
            case RESERVED -> Objects.equals(saga.getReservationRef(), reply.reservationId());
            case REJECTED ->
                saga.getStep() == RefundSagaStep.FAILED && saga.getReservationRef() == null;
          };
      return same ? Outcome.ALREADY_APPLIED : rejected(reply.refundId(), "RefundReservationResult");
    }
    switch (reply.result()) {
      case WAITING -> {
        return Outcome.WAITING;
      }
      case RESERVED -> {
        refund.reserved(now);
        saga.reserved(reply.reservationId(), now);
        jobs.save(PaymentJob.refundFor(refund, now));
      }
      case REJECTED -> {
        String reason = reply.reasonCode() == null ? RESERVATION_REJECTED : reply.reasonCode();
        refund.reservationRejected(now);
        saga.reservationRejected(reason, now);
        appendFailed(found.get(), reason, now);
      }
    }
    save(found.get());
    log(refund, "RefundReservationResult", reply.result().name());
    return Outcome.APPLIED;
  }

  /** 원장 상쇄 확인. 커머스에 주문 복구를 요청하는 RefundCompleted를 저장하고 주문 복구 확인을 기다린다. */
  @Transactional
  public Outcome applyLedgerReversed(RefundLedgerReversedPayload reply) {
    Optional<Locked> found = lock(reply.refundId(), reply.paymentId());
    if (found.isEmpty() || !reply.reservationId().equals(found.get().saga().getReservationRef())) {
      return rejected(reply.refundId(), "RefundLedgerReversed");
    }
    RefundSaga saga = found.get().saga();
    if (saga.getStep() == RefundSagaStep.ORDER_RESTORE_PENDING
        || saga.getStep() == RefundSagaStep.COMPLETED) {
      return Outcome.ALREADY_APPLIED;
    }
    if (saga.getStep() != RefundSagaStep.LEDGER_REVERSAL_PENDING) {
      return rejected(reply.refundId(), "RefundLedgerReversed");
    }
    Instant now = clock.instant();
    saga.ledgerReversed(reply.reversedAt().toInstant(), now);
    Payment payment = found.get().payment();
    events.append(
        "RefundCompleted",
        payment,
        found.get().refund(),
        new RefundCompletedPayload(
            found.get().refund().getId(), payment.getId(), payment.getOrderId()),
        now);
    save(found.get());
    log(found.get().refund(), "RefundLedgerReversed", saga.getStep().name());
    return Outcome.APPLIED;
  }

  /** 외부 환불 확정 거절 뒤 예약 해제 확인. 환불을 실패로 끝내고 RefundFailed를 저장한다. */
  @Transactional
  public Outcome applyReservationReleased(RefundReservationReleasedPayload reply) {
    Optional<Locked> found = lock(reply.refundId(), reply.paymentId());
    if (found.isEmpty() || !reply.reservationId().equals(found.get().saga().getReservationRef())) {
      return rejected(reply.refundId(), "RefundReservationReleased");
    }
    RefundSaga saga = found.get().saga();
    if (saga.getStep() == RefundSagaStep.FAILED) {
      return Outcome.ALREADY_APPLIED;
    }
    if (saga.getStep() != RefundSagaStep.RELEASE_PENDING) {
      return rejected(reply.refundId(), "RefundReservationReleased");
    }
    Instant now = clock.instant();
    saga.released(now);
    found.get().refund().compensated(now);
    appendFailed(found.get(), saga.getLastErrorCode(), now);
    save(found.get());
    log(found.get().refund(), "RefundReservationReleased", saga.getStep().name());
    return Outcome.APPLIED;
  }

  private Optional<Locked> lock(UUID refundId, UUID paymentId) {
    Optional<Refund> refund = refunds.findByIdForUpdate(refundId);
    if (refund.isEmpty() || !refund.get().getPaymentId().equals(paymentId)) {
      return Optional.empty();
    }
    RefundSaga saga = sagas.findByRefundId(refundId).orElseThrow();
    Payment payment = payments.findById(paymentId).orElseThrow();
    return Optional.of(new Locked(refund.get(), saga, payment));
  }

  private void appendFailed(Locked locked, String reasonCode, Instant now) {
    events.append(
        "RefundFailed",
        locked.payment(),
        locked.refund(),
        new RefundFailedPayload(
            locked.refund().getId(),
            locked.payment().getId(),
            locked.payment().getOrderId(),
            reasonCode,
            true),
        now);
  }

  private void save(Locked locked) {
    refunds.save(locked.refund());
    sagas.save(locked.saga());
  }

  private static Outcome rejected(UUID refundId, String reply) {
    LOG.atWarn()
        .addKeyValue("event", "payment.refund")
        .addKeyValue("refundId", refundId)
        .addKeyValue("reply", reply)
        .addKeyValue("result", Outcome.REJECTED.name())
        .log("Settlement refund reply does not match the waiting step");
    return Outcome.REJECTED;
  }

  private static void log(Refund refund, String reply, String result) {
    LOG.atInfo()
        .addKeyValue("event", "payment.refund")
        .addKeyValue("refundId", refund.getId())
        .addKeyValue("paymentId", refund.getPaymentId())
        .addKeyValue("reply", reply)
        .addKeyValue("result", result)
        .log("Settlement refund reply applied");
  }

  private record Locked(Refund refund, RefundSaga saga, Payment payment) {}
}
