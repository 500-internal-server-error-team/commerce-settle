package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.application.port.RefundGateway.RefundRequest;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundWorkerTest {
  private static final RefundWorkerSettings SETTINGS =
      new RefundWorkerSettings(Duration.ofSeconds(30), Duration.ofSeconds(5), 10);

  @Test
  @DisplayName("준비 뒤 임대에 호출 제한 시간만큼 남지 않았으면 외부 환불을 호출하지 않고 결과도 반영하지 않는다")
  void skipsExternalCallWhenLeaseExpiredAfterPrepare() {
    PaymentJobRepository jobs = mock(PaymentJobRepository.class);
    RefundResultRecorder recorder = mock(RefundResultRecorder.class);
    RefundGateway gateway = mock(RefundGateway.class);
    LeasedJob job =
        new LeasedJob(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
    RefundRequest request =
        new RefundRequest("PG_A", UUID.randomUUID(), job.businessKey(), 10_000, "pg-normal-v1");
    when(jobs.leaseNext(eq(PaymentJobOperation.REFUND), any(), eq(SETTINGS.leaseDuration())))
        .thenReturn(Optional.of(job));
    when(recorder.prepare(job))
        .thenReturn(Optional.of(new RefundResultRecorder.Prepared(Optional.of(request), true)));
    when(jobs.holdsLease(job.jobId(), job.leaseToken(), SETTINGS.httpTimeout())).thenReturn(false);

    assertThat(new RefundWorker(jobs, recorder, gateway, SETTINGS).runOnce()).isTrue();

    verifyNoInteractions(gateway);
    verify(recorder, never()).record(any(), any());
  }
}
