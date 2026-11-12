package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 원결제별 환불·복원 유일성을 DB 제약으로 확인한다. 커밋 경계를 보기 위해 자동 롤백을 끈다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaPaymentRepository.class,
  JpaRefundRepository.class,
  JpaRefundSagaRepository.class,
  JpaMileageWalletRepository.class,
  JpaMileageEntryRepository.class
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  @Autowired private PaymentRepository payments;
  @Autowired private RefundRepository refunds;
  @Autowired private RefundSagaRepository sagas;
  @Autowired private MileageWalletRepository wallets;
  @Autowired private MileageEntryRepository entries;
  @Autowired private DataSource dataSource;

  @AfterEach
  void deleteRows() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM payment.mileage_entries");
    jdbc.update("DELETE FROM payment.mileage_wallets");
    jdbc.update("DELETE FROM payment.refund_sagas");
    jdbc.update("DELETE FROM payment.refunds");
    jdbc.update("DELETE FROM payment.payments");
  }

  @Test
  @DisplayName("같은 원결제에 진행 중인 환불이 있으면 다른 요청의 환불을 저장하지 않는다")
  void rejectsSecondActiveRefundForSamePayment() {
    Payment payment = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW));

    assertThatThrownBy(() -> refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("확정 거절된 환불 뒤에는 같은 원결제의 새 환불을 저장한다")
  void acceptsNewRefundAfterDeclined() {
    Payment payment = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    Refund declined = Refund.accept(payment, UUID.randomUUID(), NOW);
    declined.reservationRejected(NOW);
    refunds.save(declined);

    Refund next = refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW));

    assertThat(refunds.findActiveByPaymentId(payment.getId()))
        .get()
        .extracting(Refund::getId)
        .isEqualTo(next.getId());
  }

  @Test
  @DisplayName("같은 취소·반품 요청의 환불은 한 건만 저장한다")
  void rejectsDuplicateOrderRefundRequest() {
    Payment first = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    Payment second = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    UUID requestId = UUID.randomUUID();
    refunds.save(Refund.accept(first, requestId, NOW));

    assertThatThrownBy(() -> refunds.save(Refund.accept(second, requestId, NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(refunds.findByOrderRefundRequestId(requestId))
        .get()
        .extracting(Refund::getPaymentId)
        .isEqualTo(first.getId());
  }

  @Test
  @DisplayName("원결제의 마일리지 복원 원장은 새 환불이 와도 한 번만 남는다")
  void recordsRestoreOncePerPayment() {
    Payment payment = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 2_000)));
    MileageWallet wallet = wallets.save(MileageWallet.open(payment.getBuyerId(), 0, NOW));
    Refund first = Refund.accept(payment, UUID.randomUUID(), NOW);
    first.reservationRejected(NOW);
    refunds.save(first);
    Refund second = refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW));
    wallet.restore(2_000, NOW);
    entries.save(MileageEntry.restore(wallet, first, NOW));

    assertThatThrownBy(() -> entries.save(MileageEntry.restore(wallet, second, NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("정산 응답을 기다리는 SAGA만 오래된 순서로 읽는다")
  void findsSagasAwaitingSettlement() {
    Payment payment = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    Refund refund = refunds.save(Refund.accept(payment, UUID.randomUUID(), NOW));
    RefundSaga waiting = sagas.save(RefundSaga.start(refund, NOW));
    Payment other = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    Refund reserved = Refund.accept(other, UUID.randomUUID(), NOW);
    RefundSaga moved = RefundSaga.start(reserved, NOW);
    reserved.reserved(NOW);
    moved.reserved(UUID.randomUUID(), NOW);
    refunds.save(reserved);
    sagas.save(moved);

    assertThat(sagas.findAwaitingSettlement(10))
        .extracting(RefundSaga::getId)
        .containsExactly(waiting.getId());
    assertThat(refunds.findById(reserved.getId()))
        .get()
        .extracting(Refund::getStatus)
        .isEqualTo(RefundStatus.EXTERNAL_PENDING);
  }

  @Test
  @DisplayName("환불 목록은 결제 소유권을 페이지와 count에 먼저 적용하고 같은 시각에는 ID 역순이다")
  void listsOnlyBuyersRefunds() {
    UUID buyer = UUID.randomUUID();
    Payment first = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0), buyer));
    Payment second = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0), buyer));
    Payment foreign = payments.save(confirmed(new PaymentAmounts(10_000, 0, 0, 0)));
    Refund a = refunds.save(Refund.accept(first, UUID.randomUUID(), NOW));
    Refund b = refunds.save(Refund.accept(second, UUID.randomUUID(), NOW));
    refunds.save(Refund.accept(foreign, UUID.randomUUID(), NOW));
    var expected =
        java.util.stream.Stream.of(a.getId(), b.getId())
            .sorted(java.util.Comparator.comparing(UUID::toString).reversed())
            .toList();
    assertThat(refunds.countByBuyer(buyer)).isEqualTo(2);
    assertThat(refunds.findByBuyer(buyer, 0, 1))
        .extracting(Refund::getId)
        .containsExactly(expected.get(0));
    assertThat(refunds.findByBuyer(buyer, 1, 1))
        .extracting(Refund::getId)
        .containsExactly(expected.get(1));
    assertThat(refunds.findByBuyer(buyer, (long) Integer.MAX_VALUE * 100, 100)).isEmpty();
    assertThat(refunds.countByBuyer(UUID.randomUUID())).isZero();
  }

  private static Payment confirmed(PaymentAmounts amounts) {
    return confirmed(amounts, UUID.randomUUID());
  }

  private static Payment confirmed(PaymentAmounts amounts, UUID buyer) {
    Payment payment =
        Payment.create(
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            buyer,
            UUID.randomUUID(),
            UUID.randomUUID(),
            amounts,
            "v1",
            NOW);
    if (amounts.externalAmount() > 0) {
      payment.startProcessing(NOW);
    }
    payment.confirm(NOW);
    return payment;
  }
}
