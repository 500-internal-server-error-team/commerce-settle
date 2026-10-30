package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.Status;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageEntryKind;
import com.commercesettle.payment.domain.model.MileageReservation;
import com.commercesettle.payment.domain.model.MileageReservationStatus;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageReservationRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApplyOrderPaymentRequestServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
  private static final UUID BUYER_ID = new UUID(0, 2);

  private final FakeInbox inbox = new FakeInbox();
  private final FakePayments payments = new FakePayments();
  private final FakeJobs jobs = new FakeJobs();
  private final FakeWallets wallets = new FakeWallets();
  private final FakeReservations reservations = new FakeReservations();
  private final FakeEntries entries = new FakeEntries();
  private final ApplyOrderPaymentRequestService service =
      new ApplyOrderPaymentRequestService(
          inbox, payments, jobs, wallets, reservations, entries, Clock.fixed(NOW, ZoneOffset.UTC));

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
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
  }

  @Test
  @DisplayName("지갑이 없는 구매자는 잔액 0으로 보고 확인 대상으로 남긴다")
  void marksReviewWhenWalletIsMissing() {
    UUID inboxId = inbox.add(mixed(UUID.randomUUID(), UUID.randomUUID()));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
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
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
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
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
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
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
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
    assertThat(inbox.reviewRequired).containsExactly(secondInboxId);
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
    assertThat(inbox.reviewRequired).containsExactly(secondInboxId);
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
    private final Queue<WaitingInboxEvent> waiting = new ArrayDeque<>();
    private final Map<UUID, OrderPaymentRequestedPayload> payloads = new HashMap<>();
    private final Map<UUID, UUID> applied = new HashMap<>();
    private final Map<UUID, OrderPaymentRequestedPayload> originals = new HashMap<>();
    private final List<UUID> reviewRequired = new ArrayList<>();

    UUID add(OrderPaymentRequestedPayload payload) {
      UUID inboxId = UUID.randomUUID();
      payloads.put(inboxId, payload);
      waiting.add(
          new WaitingInboxEvent(
              inboxId,
              new EventEnvelope<>(
                  UUID.randomUUID(),
                  "OrderPaymentRequested",
                  1,
                  OffsetDateTime.parse("2026-10-29T09:00:00+09:00"),
                  payload.orderId(),
                  0,
                  payload)));
      return inboxId;
    }

    @Override
    public void saveWaiting(EventEnvelope<OrderPaymentRequestedPayload> event, Source source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<WaitingInboxEvent> claimNextWaiting(Instant now) {
      return Optional.ofNullable(waiting.poll());
    }

    @Override
    public Optional<WaitingInboxEvent> claimWaitingEvent(UUID eventId, Instant now) {
      return claimNextWaiting(now).filter(e -> e.event().eventId().equals(eventId));
    }

    @Override
    public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
      applied.put(inboxId, resultRef);
      originals.putIfAbsent(resultRef, payloads.get(inboxId));
    }

    @Override
    public void markReviewRequired(UUID inboxId, Instant now) {
      reviewRequired.add(inboxId);
    }

    @Override
    public Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId) {
      return Optional.ofNullable(originals.get(paymentId));
    }
  }

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
    public Optional<Payment> findByOrderId(UUID orderId) {
      return byId.values().stream().filter(p -> p.getOrderId().equals(orderId)).findFirst();
    }
  }

  private static final class FakeJobs implements PaymentJobRepository {
    private final List<PaymentJob> saved = new ArrayList<>();

    @Override
    public PaymentJob save(PaymentJob job) {
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
  }

  private static final class FakeWallets implements MileageWalletRepository {
    private final Map<UUID, MileageWallet> byBuyer = new HashMap<>();
    private int lookups;

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
    public MileageWallet save(MileageWallet wallet) {
      byBuyer.put(wallet.getBuyerId(), wallet);
      return wallet;
    }
  }

  private static final class FakeReservations implements MileageReservationRepository {
    private final List<MileageReservation> saved = new ArrayList<>();

    @Override
    public MileageReservation save(MileageReservation reservation) {
      saved.add(reservation);
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
  }
}
