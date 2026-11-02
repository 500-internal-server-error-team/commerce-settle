package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercesettle.payment.application.dto.RefundView;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundQueryServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  private final RefundRepository refunds = mock(RefundRepository.class);
  private final RefundSagaRepository sagas = mock(RefundSagaRepository.class);
  private final PaymentRepository payments = mock(PaymentRepository.class);
  private final RefundQueryService service = new RefundQueryService(refunds, sagas, payments);

  @Test
  @DisplayName("본인 결제의 환불이면 상태와 SAGA 단계를 함께 돌려준다")
  void returnsOwnRefundWithSagaStep() {
    Payment payment = confirmed();
    Refund refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    given(refunds.findById(refund.getId())).willReturn(Optional.of(refund));
    given(payments.findById(payment.getId())).willReturn(Optional.of(payment));
    given(sagas.findByRefundId(refund.getId()))
        .willReturn(Optional.of(RefundSaga.start(refund, NOW)));

    Optional<RefundView> view = service.get(payment.getBuyerId(), refund.getId());

    assertThat(view)
        .get()
        .satisfies(
            v -> {
              assertThat(v.status()).isEqualTo(RefundStatus.RESERVING);
              assertThat(v.sagaStep()).isEqualTo(RefundSagaStep.RESERVATION_REQUESTED);
              assertThat(v.externalAmount()).isEqualTo(8_000);
              assertThat(v.mileageAmount()).isEqualTo(2_000);
            });
  }

  @Test
  @DisplayName("다른 구매자의 환불은 없는 환불과 같이 빈 값이다")
  void hidesOthersRefund() {
    Payment payment = confirmed();
    Refund refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    given(refunds.findById(refund.getId())).willReturn(Optional.of(refund));
    given(payments.findById(payment.getId())).willReturn(Optional.of(payment));

    assertThat(service.get(UUID.randomUUID(), refund.getId())).isEmpty();
    assertThat(service.get(payment.getBuyerId(), UUID.randomUUID())).isEmpty();
  }

  private static Payment confirmed() {
    Payment payment =
        Payment.create(
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PaymentAmounts(10_000, 0, 0, 2_000),
            "v1",
            NOW);
    payment.startProcessing(NOW);
    payment.confirm(NOW);
    return payment;
  }
}
