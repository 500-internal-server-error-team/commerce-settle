package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.repository.PaymentRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 커밋 경계를 확인하기 위해 테스트 트랜잭션 자동 롤백을 끄고 테스트마다 데이터를 지운다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaPaymentRepository.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  private static final Instant CREATED_AT = Instant.parse("2026-10-28T00:00:00.123456Z");
  private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T00:01:00.654321Z");

  @Autowired private PaymentRepository paymentRepository;
  @Autowired private DataSource dataSource;

  @AfterEach
  void deletePayments() {
    new JdbcTemplate(dataSource).update("DELETE FROM payment.payments");
  }

  @Test
  @DisplayName("저장한 결제를 다시 읽으면 금액·상태·시각이 보존된다")
  void preservesPaymentAfterReload() {
    Payment payment = payment(UUID.randomUUID(), new PaymentAmounts(23_000, 0, 3_000, 0));
    payment.startProcessing(CREATED_AT);
    payment.confirm(CONFIRMED_AT);
    paymentRepository.save(payment);

    Payment found = paymentRepository.findById(payment.getId()).orElseThrow();

    assertThat(found.getAmounts()).isEqualTo(new PaymentAmounts(23_000, 0, 3_000, 0));
    assertThat(found.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    assertThat(found.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(found.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
    assertThat(found.getUpdatedAt()).isEqualTo(CONFIRMED_AT);
    assertThat(found.getVersion()).isZero();
  }

  @Test
  @DisplayName("파생 금액 컬럼은 정책 공식대로 저장된다")
  void storesDerivedAmounts() {
    Payment payment = payment(UUID.randomUUID(), new PaymentAmounts(23_000, 0, 3_000, 0));
    paymentRepository.save(payment);

    var row =
        new JdbcTemplate(dataSource)
            .queryForMap(
                "SELECT settlement_base_amount, total_amount, external_amount, fee_amount,"
                    + " seller_net_amount, coupon_amount FROM payment.payments WHERE id = ?",
                payment.getId());

    assertThat(row)
        .containsEntry("settlement_base_amount", 23_000L)
        .containsEntry("total_amount", 20_000L)
        .containsEntry("external_amount", 20_000L)
        .containsEntry("fee_amount", 3_450L)
        .containsEntry("seller_net_amount", 19_550L)
        .containsEntry("coupon_amount", 3_000L);
  }

  @Test
  @DisplayName("주문 ID로 결제를 찾는다")
  void findsByOrderId() {
    UUID orderId = UUID.randomUUID();
    Payment payment = payment(orderId, new PaymentAmounts(10_000, 0, 0, 0));
    paymentRepository.save(payment);

    assertThat(paymentRepository.findByOrderId(orderId))
        .map(Payment::getId)
        .contains(payment.getId());
    assertThat(paymentRepository.findByOrderId(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("같은 주문의 결제는 두 번 저장할 수 없다")
  void rejectsDuplicateOrder() {
    UUID orderId = UUID.randomUUID();
    paymentRepository.save(payment(orderId, new PaymentAmounts(10_000, 0, 0, 0)));

    assertThatThrownBy(
            () -> paymentRepository.save(payment(orderId, new PaymentAmounts(10_000, 0, 0, 0))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("DB는 금액 공식에 맞지 않는 결제 행을 거절한다")
  void rejectsInconsistentAmountsInDatabase() {
    // 도메인을 우회한 저장을 가정한다. 수수료를 3,450원 대신 3,451원으로 넣는다.
    String sql =
        "INSERT INTO payment.payments VALUES (?, ?, ?, ?, 0, ?, ?, 23000, 3000, 0, 3000, 23000,"
            + " 20000, 0, 20000, 3451, 19549, 'PENDING', NULL, 'v1', now(), now(), 0)";

    assertThatThrownBy(
            () ->
                new JdbcTemplate(dataSource)
                    .update(
                        sql,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_payments_fee");
  }

  @Test
  @DisplayName("이미 다른 곳에서 수정한 결제를 이전 버전으로 저장하면 충돌한다")
  void rejectsStaleUpdate() {
    Payment payment = payment(UUID.randomUUID(), new PaymentAmounts(10_000, 0, 0, 0));
    paymentRepository.save(payment);
    Payment first = paymentRepository.findById(payment.getId()).orElseThrow();
    Payment second = paymentRepository.findById(payment.getId()).orElseThrow();

    first.startProcessing(CONFIRMED_AT);
    paymentRepository.save(first);
    second.cancel(CONFIRMED_AT);

    assertThatThrownBy(() -> paymentRepository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.PROCESSING);
  }

  private static Payment payment(UUID orderId, PaymentAmounts amounts) {
    return Payment.create(
        orderId,
        0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        amounts,
        "v1",
        CREATED_AT);
  }
}
