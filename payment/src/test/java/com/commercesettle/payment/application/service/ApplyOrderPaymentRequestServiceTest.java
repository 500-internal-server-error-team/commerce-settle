package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.Status;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationResultPayload;
import com.commercesettle.payment.application.event.PaymentConfirmedPayload;
import com.commercesettle.payment.application.event.PaymentDeclinedPayload;
import com.commercesettle.payment.application.event.RefundEventPayload;
import com.commercesettle.payment.application.port.InboxClaim;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.application.port.PaymentProcessingObservation;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageEntryKind;
import com.commercesettle.payment.domain.model.MileageReservation;
import com.commercesettle.payment.domain.model.MileageReservationStatus;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageReservationRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionOperations;

class ApplyOrderPaymentRequestServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
  private static final UUID BUYER_ID = new UUID(0, 2);

  private final FakeInbox inbox = new FakeInbox();
  private final FakePayments payments = new FakePayments();
  private final FakeJobs jobs = new FakeJobs();
  private final FakeWallets wallets = new FakeWallets();
  private final FakeReservations reservations = new FakeReservations();
  private final FakeEntries entries = new FakeEntries();
  private final FakeOutbox outbox = new FakeOutbox();
  private final ApplyOrderPaymentRequestService service =
      new ApplyOrderPaymentRequestService(
          inbox,
          payments,
          jobs,
          wallets,
          reservations,
          entries,
          outbox,
          mock(RefundEventHandler.class),
          mock(PaymentNotices.class),
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW, ZoneOffset.UTC),
          30_000,
          PaymentProcessingObservation.NOOP);

  @Test
  @DisplayName("처리할 WAITING이 없으면 아무것도 바꾸지 않는다")
  void returnsFalseWhenNothingIsWaiting() {
    assertThat(service.processNext()).isFalse();
    assertThat(payments.all()).isEmpty();
  }

  @Test
  @DisplayName("카드 결제 요청은 PENDING 결제와 승인 작업을 만들고 결제 ID로 완료한다")
  void createsPaymentAndApprovalJobForCardPayment() {
    UUID inboxId = inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    assertThat(service.processNext()).isTrue();

    Payment payment = payments.all().get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getCreatedAt()).isEqualTo(NOW);
    assertThat(jobs.saved)
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.getPaymentId()).isEqualTo(payment.getId());
              assertThat(job.getOperation()).isEqualTo(PaymentJobOperation.APPROVE);
            });
    assertThat(inbox.applied).containsEntry(inboxId, payment.getId());
  }

  @Test
  @DisplayName("새 결제를 만들면 결제 ID로 고정한 PaymentAccepted를 Outbox에 남긴다")
  void appendsPaymentAcceptedForNewPayment() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));

    service.processNext();

    Payment payment = payments.all().get(0);
    assertThat(outbox.accepted)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.eventId())
                  .isEqualTo(
                      UUID.nameUUIDFromBytes(
                          ("PaymentAccepted:" + payment.getId()).getBytes(StandardCharsets.UTF_8)));
              assertThat(event.eventType()).isEqualTo("PaymentAccepted");
              assertThat(event.eventVersion()).isEqualTo(1);
              assertThat(event.occurredAt().toInstant()).isEqualTo(NOW);
              assertThat(event.aggregateId()).isEqualTo(payment.getId());
              assertThat(event.aggregateVersion()).isZero();
              assertThat(event.payload())
                  .isEqualTo(new PaymentAcceptedPayload(payment.getId(), orderId, 0, cycleId));
            });
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 작업 없이 마일리지를 차감하고 바로 확정한다")
  void confirmsMileageOnlyPaymentWithDebit() {
    MileageWallet wallet = wallets.fund(BUYER_ID, 12_000);
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.PAYMENT_PENDING,
                10_000,
                0,
                0,
                10_000,
                10_000,
                10_000,
                0,
                0,
                null,
                null));

    service.processNext();

    Payment payment = payments.all().get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    assertThat(payment.getConfirmedAt()).isEqualTo(NOW);
    assertThat(jobs.saved).isEmpty();
    assertThat(wallet.getBalance()).isEqualTo(2_000);
    assertThat(wallet.getReservedAmount()).isZero();
    assertThat(reservations.saved)
        .singleElement()
        .extracting(MileageReservation::getStatus)
        .isEqualTo(MileageReservationStatus.CONSUMED);
    assertThat(entries.saved)
        .extracting(MileageEntry::getKind, MileageEntry::getBalanceAfter)
        .containsExactly(
            tuple(MileageEntryKind.RESERVE, 12_000L), tuple(MileageEntryKind.DEBIT, 2_000L));
    assertThat(outbox.accepted)
        .singleElement()
        .extracting(e -> e.aggregateId())
        .isEqualTo(payment.getId());
    assertThat(inbox.applied).containsEntry(inboxId, payment.getId());
  }

  @Test
  @DisplayName("마일리지와 카드를 함께 쓰면 마일리지를 예약하고 승인 작업을 남긴 채 PENDING으로 둔다")
  void reservesMileageForMixedPayment() {
    MileageWallet wallet = wallets.fund(BUYER_ID, 5_000);
    inbox.add(mixed(UUID.randomUUID(), UUID.randomUUID()));

    service.processNext();

    Payment payment = payments.all().get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(jobs.saved).hasSize(1);
    assertThat(wallet.getBalance()).isEqualTo(5_000);
    assertThat(wallet.getReservedAmount()).isEqualTo(2_000);
    assertThat(reservations.saved)
        .singleElement()
        .satisfies(
            reservation -> {
              assertThat(reservation.getStatus()).isEqualTo(MileageReservationStatus.RESERVED);
              assertThat(reservation.getPaymentId()).isEqualTo(payment.getId());
              assertThat(reservation.getAmount()).isEqualTo(2_000);
            });
    assertThat(entries.saved)
        .singleElement()
        .extracting(MileageEntry::getKind)
        .isEqualTo(MileageEntryKind.RESERVE);
  }

  @Test
  @DisplayName("가용 마일리지가 부족하면 결제를 만들지 않고 확인 대상으로 남긴다")
  void marksReviewWhenMileageIsInsufficient() {
    MileageWallet wallet = wallets.fund(BUYER_ID, 1_999);
    UUID inboxId = inbox.add(mixed(UUID.randomUUID(), UUID.randomUUID()));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(wallet.getReservedAmount()).isZero();
    assertThat(reservations.saved).isEmpty();
    assertThat(outbox.accepted).isEmpty();
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(inboxId, "PAYMENT_REQUEST_MILEAGE_INSUFFICIENT"));
  }

  @Test
  @DisplayName("지갑이 없는 구매자는 잔액 0으로 보고 확인 대상으로 남긴다")
  void marksReviewWhenWalletIsMissing() {
    UUID inboxId = inbox.add(mixed(UUID.randomUUID(), UUID.randomUUID()));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(inboxId, "PAYMENT_REQUEST_MILEAGE_INSUFFICIENT"));
  }

  @Test
  @DisplayName("마일리지를 쓰지 않는 결제는 지갑을 조회하지 않는다")
  void skipsWalletForCardOnlyPayment() {
    inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    service.processNext();

    assertThat(wallets.lookups).isZero();
    assertThat(reservations.saved).isEmpty();
  }

  @Test
  @DisplayName("커머스가 보낸 금액이 재계산과 다르면 결제를 만들지 않고 확인 대상으로 남긴다")
  void marksReviewWhenCommerceAmountsDiffer() {
    // 플랫폼 쿠폰 3,000원인데 외부 결제액을 23,000원(쿠폰 미차감)으로 보낸 경우
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.PAYMENT_PENDING,
                23_000,
                0,
                3_000,
                0,
                23_000,
                23_000,
                23_000,
                3_000,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(inboxId, "PAYMENT_REQUEST_AMOUNT_MISMATCH"));
  }

  @Test
  @DisplayName("쿠폰과 마일리지를 함께 쓴 요청은 금액 규칙 위반으로 확인 대상으로 남긴다")
  void marksReviewWhenAmountRuleIsViolated() {
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.PAYMENT_PENDING,
                10_000,
                0,
                1_000,
                500,
                10_000,
                9_000,
                8_500,
                1_000,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(inboxId, "PAYMENT_REQUEST_AMOUNT_INVALID"));
  }

  @Test
  @DisplayName("결제 대기 상태가 아닌 주문의 요청은 확인 대상으로 남긴다")
  void marksReviewWhenOrderIsNotPaymentPending() {
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.CANCELLED,
                10_000,
                0,
                0,
                0,
                10_000,
                10_000,
                10_000,
                0,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(inboxId, "PAYMENT_REQUEST_ORDER_NOT_PENDING"));
  }

  @Test
  @DisplayName("같은 주문의 같은 요청이 다시 오면 기존 결제로 완료하고 새로 만들지 않는다")
  void reusesExistingPaymentForSameRequest() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    UUID existingId = payments.all().get(0).getId();
    UUID secondInboxId = inbox.add(card(orderId, cycleId));

    service.processNext();

    assertThat(payments.all()).hasSize(1);
    assertThat(jobs.saved).hasSize(1);
    assertThat(outbox.accepted).hasSize(1);
    assertThat(inbox.applied).containsEntry(secondInboxId, existingId);
  }

  @Test
  @DisplayName("같은 주문에 다른 회차의 요청이 오면 기존 결제를 유지하고 확인 대상으로 남긴다")
  void marksReviewForDifferentRequestOfSameOrder() {
    UUID orderId = UUID.randomUUID();
    inbox.add(card(orderId, UUID.randomUUID()));
    service.processNext();
    UUID secondInboxId = inbox.add(card(orderId, UUID.randomUUID()));

    service.processNext();

    assertThat(payments.all()).hasSize(1);
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(secondInboxId, "PAYMENT_REQUEST_ORDER_CONFLICT"));
  }

  @ParameterizedTest
  @CsvSource({
    "v2, CREDIT_CARD, card-ref",
    "v1, DEBIT_CARD, card-ref",
    "v1, CREDIT_CARD, other-card-ref"
  })
  @DisplayName("같은 주문·회차·금액이어도 정책 버전이나 결제수단이 다르면 확인 대상으로 남긴다")
  void marksReviewWhenPolicyOrPaymentMethodDiffers(
      String policyVersion, ExternalMethod method, String methodAccountRef) {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    UUID secondInboxId =
        inbox.add(
            withPolicyAndMethod(card(orderId, cycleId), policyVersion, method, methodAccountRef));

    service.processNext();

    assertThat(payments.all()).hasSize(1);
    assertThat(inbox.reviewRequired)
        .containsExactly(entry(secondInboxId, "PAYMENT_REQUEST_ORDER_CONFLICT"));
  }

  @Test
  @DisplayName("마일리지 전액 결제는 결제수단 값이 달라도 같은 요청으로 본다")
  void ignoresPaymentMethodWhenExternalAmountIsZero() {
    wallets.fund(BUYER_ID, 10_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    OrderPaymentRequestedPayload mileageOnly =
        payload(
            orderId,
            cycleId,
            Status.PAYMENT_PENDING,
            10_000,
            0,
            0,
            10_000,
            10_000,
            10_000,
            0,
            0,
            null,
            null);
    inbox.add(mileageOnly);
    service.processNext();
    UUID existingId = payments.all().get(0).getId();
    UUID secondInboxId =
        inbox.add(withPolicyAndMethod(mileageOnly, "v1", ExternalMethod.EASY_PAY, "wallet-ref"));

    service.processNext();

    assertThat(inbox.applied).containsEntry(secondInboxId, existingId);
    assertThat(inbox.reviewRequired).isEmpty();
  }

  @Test
  @DisplayName("임대 시간 뒤까지 다시 잡히지 않도록 선점하고, 상한 10회를 함께 넘긴다")
  void claimsWithLeaseAndAttemptLimit() {
    inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    service.processNext();

    assertThat(inbox.claims).containsExactly(new Claim(NOW, NOW.plusSeconds(30), 10));
  }

  @Test
  @DisplayName("첫 시도가 실패하면 선점한 버전으로 실패를 기록하고 1초 뒤 다시 처리하게 한다")
  void recordsFirstFailureForRetryAfterOneSecond() {
    jobs.failSave = true;
    UUID inboxId = inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    assertThat(service.processNext()).isTrue();

    assertThat(inbox.failures)
        .containsExactly(
            new Failure(inboxId, 3, "PAYMENT_REQUEST_PROCESSING_FAILED", NOW.plusSeconds(1)));
    assertThat(inbox.applied).isEmpty();
  }

  @Test
  @DisplayName("10번째 시도가 실패하면 재시도 시각 없이 확인 대상으로 기록한다")
  void recordsTenthFailureAsReview() {
    jobs.failSave = true;
    UUID inboxId = inbox.add(card(UUID.randomUUID(), UUID.randomUUID()), 10);

    service.processNext();

    assertThat(inbox.failures)
        .containsExactly(new Failure(inboxId, 3, "PAYMENT_REQUEST_PROCESSING_FAILED", null));
  }

  @Test
  @DisplayName("선점에서 확인 대상으로 넘긴 행은 처리하지 않고 처리 1건으로 센다")
  void countsReviewedClaimAsHandledWithoutProcessing() {
    inbox.addReviewed("PAYMENT_REQUEST_ATTEMPTS_EXHAUSTED");

    assertThat(service.processNext()).isTrue();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.failures).isEmpty();
    assertThat(service.processNext()).isFalse();
  }

  @Test
  @DisplayName("임대가 끝나 다른 처리기가 선점한 요청은 처리하지 않고 건너뛴다")
  void skipsRequestWhenLeaseWasLost() {
    inbox.leaseLost = true;
    UUID inboxId = inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    assertThat(service.processNext()).isTrue();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.applied).doesNotContainKey(inboxId);
    assertThat(inbox.failures).isEmpty();
  }

  @Test
  @DisplayName("선점 자체가 실패하면 기록할 행이 없으므로 예외를 그대로 던진다")
  void rethrowsWhenClaimFails() {
    inbox.claimFailure = new IllegalStateException("database unavailable");

    assertThatThrownBy(service::processNext).hasMessage("database unavailable");
    assertThat(inbox.failures).isEmpty();
  }

  @Test
  @DisplayName("실패 기록마저 실패하면 기록 예외에 처리 실패의 원인을 함께 실어 던진다")
  void keepsProcessingCauseWhenRecordingFails() {
    jobs.failSave = true;
    inbox.recordFailure = new IllegalStateException("database unavailable");
    inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    assertThatThrownBy(service::processNext)
        .hasMessage("database unavailable")
        .satisfies(
            thrown ->
                assertThat(thrown.getSuppressed())
                    .singleElement()
                    .extracting(Throwable::getMessage)
                    .isEqualTo("job save failed"));
  }

  @ParameterizedTest
  @CsvSource({"1, 1", "2, 2", "3, 4", "6, 32", "7, 60", "9, 60"})
  @DisplayName("재시도 대기는 1초부터 두 배씩 늘고 60초를 넘지 않는다")
  void growsRetryDelayUpToSixtySeconds(int attempts, long seconds) {
    assertThat(PaymentRetryPolicy.delayAfter(attempts)).isEqualTo(Duration.ofSeconds(seconds));
  }

  @Test
  @DisplayName("승인 전 카드 결제 취소는 결제를 취소하고 승인 작업을 닫고 PaymentCancelled를 남긴다")
  void cancelsPendingCardPayment() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    Payment payment = payments.all().get(0);
    UUID cancellationId = inbox.addCancellation(orderId, cycleId);

    assertThat(service.processNext()).isTrue();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(jobs.saved.get(0).getStatus()).isEqualTo(PaymentJobStatus.DONE);
    assertThat(outbox.cancellations)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.eventType()).isEqualTo("PaymentCancelled");
              assertThat(event.aggregateVersion()).isEqualTo(1);
              assertThat(event.payload())
                  .isEqualTo(
                      new PaymentCancellationResultPayload(
                          payment.getId(), orderId, cycleId, "CANCELLED_BEFORE_APPROVAL"));
            });
    assertThat(inbox.applied).containsEntry(cancellationId, payment.getId());
  }

  @Test
  @DisplayName("승인 전 혼합 결제 취소는 마일리지 예약을 풀고 해제 원장을 남긴다")
  void releasesMileageWhenCancellingMixedPayment() {
    MileageWallet wallet = wallets.fund(BUYER_ID, 5_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(mixed(orderId, cycleId));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);

    service.processNext();

    assertThat(wallet.getBalance()).isEqualTo(5_000);
    assertThat(wallet.getReservedAmount()).isZero();
    assertThat(reservations.saved)
        .singleElement()
        .extracting(MileageReservation::getStatus)
        .isEqualTo(MileageReservationStatus.RELEASED);
    assertThat(entries.saved)
        .extracting(MileageEntry::getKind)
        .containsExactly(MileageEntryKind.RESERVE, MileageEntryKind.RELEASE);
  }

  @Test
  @DisplayName("확정된 결제의 취소 요청은 결제를 그대로 두고 PaymentCancellationRejected를 남긴다")
  void rejectsCancellationOfConfirmedPayment() {
    wallets.fund(BUYER_ID, 12_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(
        payload(
            orderId,
            cycleId,
            Status.PAYMENT_PENDING,
            10_000,
            0,
            0,
            10_000,
            10_000,
            10_000,
            0,
            0,
            null,
            null));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);

    service.processNext();

    assertThat(payments.all().get(0).getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    assertThat(outbox.cancellations)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.eventType()).isEqualTo("PaymentCancellationRejected");
              assertThat(event.payload().reasonCode()).isEqualTo("PAYMENT_ALREADY_CONFIRMED");
            });
  }

  @Test
  @DisplayName("외부 승인을 시작한 결제는 취소하지 않고 원거래 결과를 기다리도록 거절한다")
  void rejectsCancellationWhilePaymentIsProcessing() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    Payment payment = payments.all().get(0);
    payment.startProcessing(NOW);
    inbox.addCancellation(orderId, cycleId);

    service.processNext();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(jobs.saved.get(0).getStatus()).isEqualTo(PaymentJobStatus.PENDING);
    assertThat(outbox.cancellations)
        .singleElement()
        .extracting(event -> event.payload().reasonCode())
        .isEqualTo("PAYMENT_IN_PROGRESS");
  }

  @Test
  @DisplayName("다른 결제 회차의 취소 요청은 거절한다")
  void rejectsCancellationOfOtherCycle() {
    UUID orderId = UUID.randomUUID();
    inbox.add(card(orderId, UUID.randomUUID()));
    service.processNext();
    inbox.addCancellation(orderId, UUID.randomUUID());

    service.processNext();

    assertThat(payments.all().get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(outbox.cancellations)
        .singleElement()
        .extracting(event -> event.payload().reasonCode())
        .isEqualTo("PAYMENT_CYCLE_MISMATCH");
  }

  @Test
  @DisplayName("이미 취소한 결제에 취소 요청이 다시 오면 결과를 다시 만들지 않는다")
  void ignoresRepeatedCancellation() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);
    service.processNext();
    UUID second = inbox.addCancellation(orderId, cycleId);

    service.processNext();

    assertThat(outbox.cancellations).hasSize(1);
    assertThat(inbox.applied).containsKey(second);
  }

  @Test
  @DisplayName("결제 요청이 확인 대상으로 끝난 주문의 취소는 그 원문으로 취소된 결제를 만들고 PaymentCancelled를 남긴다")
  void cancelsOrderWhosePaymentRequestEndedInReview() {
    wallets.fund(BUYER_ID, 1_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID requestId = inbox.add(mixed(orderId, cycleId));
    service.processNext();
    assertThat(inbox.reviewRequired).containsKey(requestId);
    UUID cancellationId = inbox.addCancellation(orderId, cycleId);

    service.processNext();

    Payment payment = payments.all().get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(jobs.saved).isEmpty();
    assertThat(reservations.saved).isEmpty();
    assertThat(outbox.accepted).hasSize(1);
    assertThat(outbox.cancellations)
        .singleElement()
        .extracting(event -> event.payload().reasonCode())
        .isEqualTo("CANCELLED_BEFORE_PAYMENT");
    assertThat(inbox.applied).containsEntry(cancellationId, payment.getId());
  }

  @Test
  @DisplayName("결제 요청과 취소 요청 모두 주문 단위 잠금을 먼저 잡는다")
  void locksOrderBeforeProcessing() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    inbox.addCancellation(orderId, cycleId);

    service.processNext();
    service.processNext();

    assertThat(inbox.lockedOrders).containsExactly(orderId, orderId);
  }

  @Test
  @DisplayName("결제 요청보다 취소가 먼저 오면 늦은 결제 요청은 결제를 취소 상태로 만들고 승인·마일리지 예약을 하지 않는다")
  void cancelsLatePaymentRequestAfterEarlyCancellation() {
    MileageWallet wallet = wallets.fund(BUYER_ID, 5_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID cancellationId = inbox.addCancellation(orderId, cycleId);
    service.processNext();
    assertThat(payments.all()).isEmpty();
    UUID requestId = inbox.add(mixed(orderId, cycleId));

    service.processNext();

    Payment payment = payments.all().get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(jobs.saved).isEmpty();
    assertThat(reservations.saved).isEmpty();
    assertThat(wallet.getReservedAmount()).isZero();
    assertThat(outbox.accepted).hasSize(1);
    assertThat(outbox.cancellations)
        .singleElement()
        .extracting(event -> event.payload().reasonCode())
        .isEqualTo("CANCELLED_BEFORE_PAYMENT");
    assertThat(inbox.applied).containsEntry(requestId, payment.getId());
    assertThat(inbox.linked).containsEntry(cancellationId, payment.getId());
  }

  @Test
  @DisplayName("취소된 결제라도 정책이 달라진 재요청은 충돌로 남긴다")
  void rejectsChangedPolicyAfterCancellation() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);
    service.processNext();
    UUID conflicting =
        inbox.add(
            withPolicyAndMethod(
                card(orderId, cycleId), "v2", ExternalMethod.CREDIT_CARD, "card-ref"));

    service.processNext();

    assertThat(inbox.reviewRequired).containsEntry(conflicting, "PAYMENT_REQUEST_ORDER_CONFLICT");
    assertThat(inbox.applied).doesNotContainKey(conflicting);
    assertThat(outbox.cancellations).hasSize(1);
  }

  @Test
  @DisplayName("검토 대상 원문으로 취소한 뒤 동일 요청이 오면 원문 이력을 보존하고 기존 결제로 연결한다")
  void acceptsSameRequestAfterReviewedCancellation() {
    wallets.fund(BUYER_ID, 1_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID original = inbox.add(mixed(orderId, cycleId));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);
    service.processNext();
    UUID replay = inbox.add(mixed(orderId, cycleId));

    service.processNext();

    assertThat(inbox.applied).containsEntry(replay, payments.all().getFirst().getId());
    assertThat(inbox.reviewRequired).containsKey(original);
    assertThat(jobs.saved).isEmpty();
    assertThat(outbox.cancellations).hasSize(1);
  }

  @ParameterizedTest
  @CsvSource({
    "v2, CREDIT_CARD, card-ref",
    "v1, DEBIT_CARD, card-ref",
    "v1, CREDIT_CARD, other-card-ref"
  })
  @DisplayName("취소 선처리로 만든 결제도 정책·결제수단이 다른 재요청은 거절한다")
  void rejectsConflictingReplayAfterReviewedCancellation(
      String policy, ExternalMethod method, String account) {
    wallets.fund(BUYER_ID, 1_000);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(mixed(orderId, cycleId));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);
    service.processNext();
    UUID conflict =
        inbox.add(withPolicyAndMethod(mixed(orderId, cycleId), policy, method, account));

    service.processNext();

    assertThat(inbox.reviewRequired).containsEntry(conflict, "PAYMENT_REQUEST_ORDER_CONFLICT");
    assertThat(inbox.applied).doesNotContainKey(conflict);
    assertThat(outbox.cancellations).hasSize(1);
  }

  @Test
  @DisplayName("취소한 주문의 금액이 바뀐 요청은 원결제를 바꾸지 않고 충돌로 남긴다")
  void rejectsChangedAmountAfterCancellation() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    inbox.addCancellation(orderId, cycleId);
    service.processNext();
    UUID conflict =
        inbox.add(
            payload(
                orderId,
                cycleId,
                Status.PAYMENT_PENDING,
                20_000,
                0,
                0,
                0,
                20_000,
                20_000,
                20_000,
                0,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(inbox.reviewRequired).containsEntry(conflict, "PAYMENT_REQUEST_ORDER_CONFLICT");
    assertThat(payments.all().getFirst().getAmounts().grossAmount()).isEqualTo(10_000);
    assertThat(outbox.cancellations).hasSize(1);
  }

  @Test
  @DisplayName("결제 대기가 아닌 원문은 취소 선처리에서도 승인된 요청으로 사용하지 않는다")
  void rejectsNonPendingSourceForCancellation() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(
        payload(
            orderId,
            cycleId,
            Status.PAID,
            10_000,
            0,
            0,
            0,
            10_000,
            10_000,
            10_000,
            0,
            ExternalMethod.CREDIT_CARD,
            "card-ref"));
    service.processNext();
    UUID cancellation = inbox.addCancellation(orderId, cycleId);

    service.processNext();

    assertThat(inbox.reviewRequired)
        .containsEntry(cancellation, "PAYMENT_CANCELLATION_REQUEST_INVALID");
    assertThat(payments.all()).isEmpty();
    assertThat(outbox.cancellations).isEmpty();
  }

  private static OrderPaymentRequestedPayload mixed(UUID orderId, UUID cycleId) {
    return payload(
        orderId,
        cycleId,
        Status.PAYMENT_PENDING,
        10_000,
        0,
        0,
        2_000,
        10_000,
        10_000,
        8_000,
        0,
        ExternalMethod.CREDIT_CARD,
        "card-ref");
  }

  private static OrderPaymentRequestedPayload card(UUID orderId, UUID cycleId) {
    return payload(
        orderId,
        cycleId,
        Status.PAYMENT_PENDING,
        10_000,
        0,
        0,
        0,
        10_000,
        10_000,
        10_000,
        0,
        ExternalMethod.CREDIT_CARD,
        "card-ref");
  }

  private static OrderPaymentRequestedPayload payload(
      UUID orderId,
      UUID cycleId,
      Status status,
      long gross,
      long sellerCoupon,
      long platformCoupon,
      long mileage,
      long base,
      long total,
      long external,
      long coupon,
      ExternalMethod method,
      String methodAccountRef) {
    return new OrderPaymentRequestedPayload(
        orderId,
        new UUID(0, 2),
        new UUID(0, 3),
        cycleId,
        new UUID(0, 5),
        0,
        status,
        "v1",
        OffsetDateTime.parse("2026-10-29T09:10:00+09:00"),
        method,
        methodAccountRef,
        gross,
        coupon,
        total,
        mileage,
        external,
        sellerCoupon,
        platformCoupon,
        base);
  }

  private static OrderPaymentRequestedPayload withPolicyAndMethod(
      OrderPaymentRequestedPayload source,
      String policyVersion,
      ExternalMethod method,
      String methodAccountRef) {
    return new OrderPaymentRequestedPayload(
        source.orderId(),
        source.buyerId(),
        source.sellerId(),
        source.paymentCycleId(),
        source.productId(),
        source.orderVersion(),
        source.status(),
        policyVersion,
        source.paymentWaitUntil(),
        method,
        methodAccountRef,
        source.grossAmount(),
        source.couponAmount(),
        source.totalAmount(),
        source.mileageAmount(),
        source.externalAmount(),
        source.sellerCouponAmount(),
        source.platformCouponAmount(),
        source.settlementBaseAmount());
  }

  private static final class FakeInbox implements InboxStore {
    private final Queue<InboxClaim> waiting = new ArrayDeque<>();
    private final Map<UUID, OrderPaymentRequestedPayload> payloads = new LinkedHashMap<>();
    private final List<UUID> lockedOrders = new ArrayList<>();
    private final Map<UUID, UUID> applied = new HashMap<>();
    private final Map<UUID, OrderPaymentRequestedPayload> originals = new HashMap<>();
    private final Map<UUID, String> reviewRequired = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private final List<Claim> claims = new ArrayList<>();
    private RuntimeException claimFailure;
    private RuntimeException recordFailure;
    private boolean leaseLost;
    private final Map<UUID, UUID[]> cancellations = new HashMap<>();
    private final Map<UUID, UUID> linked = new HashMap<>();

    UUID add(OrderPaymentRequestedPayload payload) {
      return add(payload, 1);
    }

    UUID add(OrderPaymentRequestedPayload payload, int attempts) {
      UUID inboxId = UUID.randomUUID();
      payloads.put(inboxId, payload);
      waiting.add(
          new InboxClaim.Claimed(
              new WaitingInboxEvent(
                  inboxId,
                  3,
                  attempts,
                  NOW,
                  new EventEnvelope<>(
                      UUID.randomUUID(),
                      "OrderPaymentRequested",
                      1,
                      OffsetDateTime.parse("2026-10-29T09:00:00+09:00"),
                      payload.orderId(),
                      0,
                      payload))));
      return inboxId;
    }

    UUID addCancellation(UUID orderId, UUID cycleId) {
      UUID inboxId = UUID.randomUUID();
      waiting.add(
          new InboxClaim.Claimed(
              new WaitingInboxEvent(
                  inboxId,
                  3,
                  1,
                  NOW,
                  new EventEnvelope<>(
                      UUID.randomUUID(),
                      "PaymentCancellationRequested",
                      1,
                      OffsetDateTime.parse("2026-10-29T09:05:00+09:00"),
                      orderId,
                      1,
                      new PaymentCancellationRequestedPayload(
                          orderId, 1, cycleId, UUID.randomUUID())))));
      cancellations.put(inboxId, new UUID[] {orderId, cycleId});
      return inboxId;
    }

    UUID addReviewed(String errorCode) {
      UUID inboxId = UUID.randomUUID();
      waiting.add(new InboxClaim.Reviewed(inboxId, errorCode));
      return inboxId;
    }

    @Override
    public void saveWaiting(EventEnvelope<?> event, Source source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<InboxClaim> claimNextWaiting(Instant now, Instant leaseUntil, int maxAttempts) {
      if (claimFailure != null) {
        throw claimFailure;
      }
      claims.add(new Claim(now, leaseUntil, maxAttempts));
      return Optional.ofNullable(waiting.poll());
    }

    @Override
    public Optional<InboxClaim> claimWaitingEvent(
        UUID eventId, Instant now, Instant leaseUntil, int maxAttempts) {
      return claimNextWaiting(now, leaseUntil, maxAttempts)
          .filter(
              claim ->
                  claim instanceof InboxClaim.Claimed(WaitingInboxEvent e)
                      && e.event().eventId().equals(eventId));
    }

    @Override
    public void lockOrder(UUID orderId) {
      lockedOrders.add(orderId);
    }

    @Override
    public boolean lockClaimed(UUID inboxId, long version) {
      return !leaseLost && !applied.containsKey(inboxId);
    }

    @Override
    public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
      applied.put(inboxId, resultRef);
      if (resultRef != null && payloads.containsKey(inboxId)) {
        originals.putIfAbsent(resultRef, payloads.get(inboxId));
      }
    }

    @Override
    public Optional<UUID> findEarlyCancellation(UUID orderId, UUID paymentCycleId) {
      return cancellations.entrySet().stream()
          .filter(e -> applied.containsKey(e.getKey()) && applied.get(e.getKey()) == null)
          .filter(e -> !linked.containsKey(e.getKey()))
          .filter(e -> e.getValue()[0].equals(orderId) && e.getValue()[1].equals(paymentCycleId))
          .map(Map.Entry::getKey)
          .findFirst();
    }

    @Override
    public void linkResult(UUID inboxId, UUID resultRef, Instant now) {
      linked.put(inboxId, resultRef);
    }

    @Override
    public Optional<PaymentRequest> findPaymentRequest(UUID orderId, UUID paymentCycleId) {
      return payloads.entrySet().stream()
          .filter(
              e ->
                  e.getValue().orderId().equals(orderId)
                      && e.getValue().paymentCycleId().equals(paymentCycleId))
          .map(e -> new PaymentRequest(e.getKey(), e.getValue()))
          .findFirst();
    }

    @Override
    public void linkPaymentRequest(UUID inboxId, UUID paymentId, Instant now) {
      originals.put(paymentId, payloads.get(inboxId));
      linked.put(inboxId, paymentId);
      if (!reviewRequired.containsKey(inboxId)) {
        markApplied(inboxId, paymentId, now);
      }
    }

    @Override
    public void markReviewRequired(UUID inboxId, String errorCode, Instant now) {
      reviewRequired.put(inboxId, errorCode);
    }

    @Override
    public boolean recordFailure(
        UUID inboxId, long version, String errorCode, Instant retryAt, Instant now) {
      if (recordFailure != null) {
        throw recordFailure;
      }
      failures.add(new Failure(inboxId, version, errorCode, retryAt));
      return true;
    }

    @Override
    public Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId) {
      return Optional.ofNullable(originals.get(paymentId));
    }
  }

  private record Failure(UUID inboxId, long version, String errorCode, Instant retryAt) {}

  private record Claim(Instant now, Instant leaseUntil, int maxAttempts) {}

  private static final class FakePayments implements PaymentRepository {
    private final Map<UUID, Payment> byId = new HashMap<>();

    List<Payment> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public Payment save(Payment payment) {
      byId.put(payment.getId(), payment);
      return payment;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Payment> findByIdForUpdate(UUID id) {
      return findById(id);
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
      return byId.values().stream().filter(p -> p.getOrderId().equals(orderId)).findFirst();
    }

    @Override
    public Optional<Payment> findByIdAndBuyerId(UUID id, UUID buyerId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Payment> findByBuyer(UUID buyerId, UUID orderId, int page, int size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countByBuyer(UUID buyerId, UUID orderId) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeJobs implements PaymentJobRepository {
    private final List<PaymentJob> saved = new ArrayList<>();
    private boolean failSave;

    @Override
    public PaymentJob save(PaymentJob job) {
      if (failSave) {
        throw new IllegalStateException("job save failed");
      }
      saved.add(job);
      return job;
    }

    @Override
    public Optional<PaymentJob> findByOperationAndBusinessKey(
        PaymentJobOperation operation, UUID businessKey) {
      return saved.stream()
          .filter(j -> j.getOperation() == operation && j.getBusinessKey().equals(businessKey))
          .findFirst();
    }

    @Override
    public Optional<LeasedJob> leaseNext(
        PaymentJobOperation operation, String owner, Duration leaseDuration) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean holdsLease(UUID jobId, UUID leaseToken, Duration minRemaining) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean releaseLease(
        UUID jobId, UUID leaseToken, PaymentJobStatus status, Duration runAfter, String errorCode) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PaymentJob> findById(UUID jobId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PaymentJob> findPage(int page, int size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long count() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReplayResult replay(UUID jobId, long expectedVersion) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeWallets implements MileageWalletRepository {
    private final Map<UUID, MileageWallet> byBuyer = new HashMap<>();
    private int lookups;

    @Override
    public MileageWallet saveAndFlush(MileageWallet wallet) {
      return save(wallet);
    }

    MileageWallet fund(UUID buyerId, long balance) {
      MileageWallet wallet = MileageWallet.open(buyerId, balance, NOW);
      byBuyer.put(buyerId, wallet);
      return wallet;
    }

    @Override
    public Optional<MileageWallet> findByBuyerIdForUpdate(UUID buyerId) {
      lookups++;
      return Optional.ofNullable(byBuyer.get(buyerId));
    }

    @Override
    public Optional<MileageWallet> findByBuyerId(UUID buyerId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MileageWallet save(MileageWallet wallet) {
      byBuyer.put(wallet.getBuyerId(), wallet);
      return wallet;
    }
  }

  private static final class FakeReservations implements MileageReservationRepository {
    private final List<MileageReservation> saved = new ArrayList<>();

    @Override
    public MileageReservation save(MileageReservation reservation) {
      if (!saved.contains(reservation)) {
        saved.add(reservation);
      }
      return reservation;
    }

    @Override
    public Optional<MileageReservation> findByPaymentId(UUID paymentId) {
      return saved.stream().filter(r -> r.getPaymentId().equals(paymentId)).findFirst();
    }
  }

  private static final class FakeEntries implements MileageEntryRepository {
    private final List<MileageEntry> saved = new ArrayList<>();

    @Override
    public MileageEntry save(MileageEntry entry) {
      saved.add(entry);
      return entry;
    }

    @Override
    public List<MileageEntry> findByWalletId(UUID walletId, int page, int size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countByWalletId(UUID walletId) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeOutbox implements PaymentEventOutbox {
    @Override
    public UUID appendNoticeRequested(
        EventEnvelope<com.commercesettle.payment.application.event.PaymentNoticeRequestedPayload>
            event) {
      return event.eventId();
    }

    private final List<EventEnvelope<PaymentAcceptedPayload>> accepted = new ArrayList<>();
    private final List<EventEnvelope<PaymentCancellationResultPayload>> cancellations =
        new ArrayList<>();
    private final List<EventEnvelope<PaymentConfirmedPayload>> confirmed = new ArrayList<>();

    @Override
    public UUID appendPaymentConfirmed(EventEnvelope<PaymentConfirmedPayload> event) {
      confirmed.add(event);
      return event.eventId();
    }

    @Override
    public UUID appendPaymentDeclined(EventEnvelope<PaymentDeclinedPayload> event) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID appendCancellationResult(EventEnvelope<PaymentCancellationResultPayload> event) {
      cancellations.add(event);
      return event.eventId();
    }

    @Override
    public UUID appendPaymentAccepted(EventEnvelope<PaymentAcceptedPayload> event) {
      accepted.add(event);
      return event.eventId();
    }

    @Override
    public UUID appendRefundEvent(EventEnvelope<? extends RefundEventPayload> event) {
      throw new UnsupportedOperationException();
    }
  }
}
