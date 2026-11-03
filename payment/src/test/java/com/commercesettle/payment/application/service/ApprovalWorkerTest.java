package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalWorkerTest {
  private static final ApprovalWorkerSettings SETTINGS =
      new ApprovalWorkerSettings(
          Duration.ofSeconds(30),
          Duration.ofSeconds(5),
          10,
          "PG_A",
          "payout-funding-default",
          "pg-normal-v1");

  @Test
  @DisplayName("준비 뒤 임대에 호출 제한 시간만큼 남지 않았으면 외부 승인을 호출하지 않고 결과도 반영하지 않는다")
  void skipsExternalCallWhenLeaseExpiredAfterPrepare() {
    PaymentJobRepository jobs = mock(PaymentJobRepository.class);
    ApprovalResultRecorder recorder = mock(ApprovalResultRecorder.class);
    PaymentGateway gateway = mock(PaymentGateway.class);
    LeasedJob job =
        new LeasedJob(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
    ApprovalRequest request =
        new ApprovalRequest(
            "PG_A",
            job.businessKey(),
            "CREDIT_CARD",
            "card-ref",
            "payout-funding-default",
            10_000,
            "pg-normal-v1");
    when(jobs.leaseNext(eq(PaymentJobOperation.APPROVE), any(), eq(SETTINGS.leaseDuration())))
        .thenReturn(Optional.of(job));
    when(recorder.prepare(job))
        .thenReturn(Optional.of(new ApprovalResultRecorder.Prepared(request, true)));
    when(jobs.holdsLease(job.jobId(), job.leaseToken(), SETTINGS.httpTimeout())).thenReturn(false);

    assertThat(new ApprovalWorker(jobs, recorder, gateway, SETTINGS).runOnce()).isTrue();

    verifyNoInteractions(gateway);
    verify(recorder, never()).record(any(), any());
  }
}
