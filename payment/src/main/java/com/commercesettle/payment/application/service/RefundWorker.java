package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.application.port.RefundGateway.RefundRequest;
import com.commercesettle.payment.application.service.RefundResultRecorder.RefundLeaseLostException;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 환불 Worker. 정산금 예약이 확인된 환불 작업 하나를 임대해 원외부 거래에 외부 결제액만 환불하고, 성공을 확인하면 사용 마일리지를 복원한다. 처음 호출하는 환불만 실행
 * 요청을 보내고, 결과를 모르는 환불은 원거래 조회부터 한다. 조회가 거래 부재(404)면 임대에 호출 제한 시간만큼 남았는지 다시 확인한 뒤 같은 업무 키·본문으로
 * 재전송한다.
 */
@Service
public class RefundWorker {
  private static final Logger LOG = LoggerFactory.getLogger(RefundWorker.class);

  private final PaymentJobRepository jobs;
  private final RefundResultRecorder recorder;
  private final RefundGateway gateway;
  private final RefundWorkerSettings settings;
  private final String owner = "payment-refund-" + UUID.randomUUID();

  RefundWorker(
      PaymentJobRepository jobs,
      RefundResultRecorder recorder,
      RefundGateway gateway,
      RefundWorkerSettings settings) {
    this.jobs = jobs;
    this.recorder = recorder;
    this.gateway = gateway;
    this.settings = settings;
  }

  /** 도래한 작업이 없으면 false를 돌려준다. */
  public boolean runOnce() {
    Optional<LeasedJob> leased =
        jobs.leaseNext(PaymentJobOperation.REFUND, owner, settings.leaseDuration());
    if (leased.isEmpty()) {
      return false;
    }
    LeasedJob job = leased.get();
    Optional<RefundResultRecorder.Prepared> prepared = recorder.prepare(job);
    if (prepared.isEmpty()) {
      return true;
    }
    Optional<Result> result =
        prepared.get().request().map(request -> call(job, request, prepared.get().firstCall()));
    try {
      recorder.record(job, result);
    } catch (RefundLeaseLostException e) {
      LOG.atWarn()
          .addKeyValue("event", "payment.refund")
          .addKeyValue("paymentId", job.paymentId())
          .addKeyValue("jobId", job.jobId())
          .addKeyValue("result", "LEASE_LOST")
          .log("Refund result was not recorded because the lease was lost");
    }
    return true;
  }

  private Result call(LeasedJob job, RefundRequest request, boolean firstCall) {
    if (firstCall) {
      return gateway.refund(request);
    }
    Result found = gateway.lookup(request);
    if (found instanceof Result.NotFound
        && jobs.holdsLease(job.jobId(), job.leaseToken(), settings.httpTimeout())) {
      return gateway.refund(request);
    }
    return found;
  }
}
