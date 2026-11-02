package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.event.RefundReservationResultPayload;
import com.commercesettle.settlement.application.event.RefundReservationResultPayload.Result;
import com.commercesettle.settlement.application.port.InboxStore;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.application.port.SettlementEventOutbox;
import com.commercesettle.settlement.domain.repository.RefundReservationRepository;
import com.commercesettle.settlement.domain.repository.RefundReservationRepository.ItemState;
import com.commercesettle.settlement.domain.repository.RefundReservationRepository.Reservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전액 환불 SAGA의 Settlement 몫을 Inbox 저장과 같은 트랜잭션에 반영하고 결과를 Outbox에 남긴다. 발행 이벤트의 집계는 결제(항목과 1:1)이고 집계
 * 버전은 항목 버전이라, 항목이 생기기 전 WAITING과 뒤의 결과가 같은 순서로 나간다. 같은 환불의 다른 eventId는 이미 처리한 사실과 같으면 결과를 다시 쓰지
 * 않고, 다르면 운영 확인으로 남긴다.
 */
@Service
public class RefundEventService {
  private static final Logger LOGGER = LoggerFactory.getLogger(RefundEventService.class);
  // policies.md 재처리: 최대 10회, 1초부터 최대 60초 간격, 소진 시 REVIEW_REQUIRED.
  private static final int MAX_WAITING_ATTEMPTS = 10;
  static final String PAYOUT_RESERVED = "PAYOUT_RESERVED";
  static final String ALREADY_REFUNDED = "ALREADY_REFUNDED";
  static final String REFUND_AMOUNT_MISMATCH = "REFUND_AMOUNT_MISMATCH";

  private final InboxStore inbox;
  private final RefundReservationRepository reservations;
  private final SettlementEventOutbox outbox;
  private final Clock clock;
  private final Duration waitingRecheckDelay;

  RefundEventService(
      InboxStore inbox,
      RefundReservationRepository reservations,
      SettlementEventOutbox outbox,
      Clock clock,
      @Value("${settlement.inbox-processor.interval-ms}") long waitingRecheckMillis) {
    this.inbox = inbox;
    this.reservations = reservations;
    this.outbox = outbox;
    this.clock = clock;
    this.waitingRecheckDelay = Duration.ofMillis(waitingRecheckMillis);
  }

  @Transactional
  public void receiveReservationRequested(
      EventEnvelope<RefundReservationRequestedPayload> event, Source source) {
    Optional<UUID> inboxId = inbox.receive(event, source);
    if (inboxId.isPresent()) {
      reserve(inboxId.get(), event, true);
    }
  }

  /** 처리기가 잠근 WAITING 예약 요청을 다시 판정한다. 호출자의 트랜잭션에 참여한다. */
  @Transactional
  public void processWaitingReservation(
      UUID inboxId, EventEnvelope<RefundReservationRequestedPayload> event) {
    reserve(inboxId, event, false);
  }

  private void reserve(
      UUID inboxId, EventEnvelope<RefundReservationRequestedPayload> event, boolean firstReceipt) {
    Instant now = clock.instant();
    RefundReservationRequestedPayload request = event.payload();
    Optional<ItemState> found = reservations.findItem(request.paymentId());
    if (found.isEmpty()) {
      // 결제 원장이 아직 없다. 처음 대기할 때만 WAITING을 알리고, 상한을 소진하면 결과 없이 운영 확인으로 남긴다.
      if (!inbox.markWaiting(inboxId, waitingRecheckDelay, MAX_WAITING_ATTEMPTS, now)) {
        logReview(event, "PAYMENT_NOT_ARRIVED");
      } else if (firstReceipt) {
        publish(request, Result.WAITING, null, null, 0, now);
      }
      return;
    }
    ItemState item = found.get();
    Optional<Reservation> same = reservations.find(item.id(), request.refundId());
    if (same.isPresent()) {
      if (same.get().amount() == request.netAmount()) {
        inbox.markApplied(inboxId, same.get().id(), now);
      } else {
        review(inboxId, event, "REFUND_ALREADY_RESERVED_WITH_DIFFERENT_VALUES", now);
      }
      return;
    }
    Optional<String> rejection = rejection(item, request);
    if (rejection.isEmpty()) {
      Optional<Reservation> reserved = reservations.reserve(item.id(), request.refundId(), now);
      if (reserved.isPresent()) {
        inbox.markApplied(inboxId, reserved.get().id(), now);
        publish(
            request, Result.RESERVED, reserved.get().id(), null, reserved.get().itemVersion(), now);
        return;
      }
      // 읽은 뒤 지급 선점이 먼저 바꿨다. 지금 상태로 사유를 다시 정한다.
      item = reservations.findItem(request.paymentId()).orElseThrow();
      rejection = rejection(item, request);
    }
    inbox.markApplied(inboxId, item.id(), now);
    publish(
        request, Result.REJECTED, null, rejection.orElse(ALREADY_REFUNDED), item.version(), now);
  }

  /** 예약할 수 없는 사유. 지급 예약 후·지급 후 환불과 부분 환불은 지원하지 않는다. */
  private Optional<String> rejection(ItemState item, RefundReservationRequestedPayload request) {
    if (item.payoutId() != null) {
      return Optional.of(PAYOUT_RESERVED);
    }
    return switch (item.status()) {
      case PAYOUT_RESERVED, PAID -> Optional.of(PAYOUT_RESERVED);
      case REFUND_RESERVED, REFUNDED -> Optional.of(ALREADY_REFUNDED);
      case WAITING_CONFIRMATION, PAYABLE ->
          item.netAmount() == request.netAmount()
              ? Optional.empty()
              : Optional.of(REFUND_AMOUNT_MISMATCH);
    };
  }

  private void publish(
      RefundReservationRequestedPayload request,
      Result result,
      UUID reservationId,
      String reasonCode,
      long itemVersion,
      Instant now) {
    outbox.append(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "RefundReservationResult",
            1,
            now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
            request.paymentId(),
            itemVersion,
            new RefundReservationResultPayload(
                request.refundId(), request.paymentId(), result, reservationId, reasonCode)),
        request.paymentId());
  }

  private void review(UUID inboxId, EventEnvelope<?> event, String reason, Instant now) {
    inbox.markReviewRequired(inboxId, now);
    logReview(event, reason);
  }

  private static void logReview(EventEnvelope<?> event, String reason) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "settlement.inbox")
        .addKeyValue("eventId", event.eventId())
        .addKeyValue("eventType", event.eventType())
        .addKeyValue("result", "REVIEW_REQUIRED")
        .addKeyValue("reason", reason)
        .log("Settlement event requires review");
  }
}
