package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.service.ApprovalResultRecorder.ApprovalLeaseLostException;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 외부 승인 Worker. 도래한 승인 작업 하나를 임대해 PG 대역을 호출하고 결과를 반영한다. 처음 실행하는 결제만 승인 요청을 보내고, 결과를 모르는 결제는 원거래
 * 조회부터 한다. 조회가 거래 부재(404)면 임대에 호출 제한 시간만큼 남았는지 다시 확인한 뒤 같은 업무 키·본문으로 재전송한다.
 */
@Service
public class ApprovalWorker {
  private static final Logger LOG = LoggerFactory.getLogger(ApprovalWorker.class);

  private final PaymentJobRepository jobs;
  private final ApprovalResultRecorder recorder;
  private final PaymentGateway gateway;
  private final ApprovalWorkerSettings settings;
  private final String owner = "payment-" + UUID.randomUUID();

  ApprovalWorker(
      PaymentJobRepository jobs,
      ApprovalResultRecorder recorder,
      PaymentGateway gateway,
      ApprovalWorkerSettings settings) {
    this.jobs = jobs;
    this.recorder = recorder;
    this.gateway = gateway;
    this.settings = settings;
  }

  /** 도래한 작업이 없으면 false를 돌려준다. */
  public boolean runOnce() {
    Optional<LeasedJob> leased =
        jobs.leaseNext(PaymentJobOperation.APPROVE, owner, settings.leaseDuration());
    if (leased.isEmpty()) {
      return false;
    }
    LeasedJob job = leased.get();
    Optional<ApprovalResultRecorder.Prepared> prepared = recorder.prepare(job);
    if (prepared.isEmpty()) {
      return true;
    }
    Result result = call(job, prepared.get());
    try {
      recorder.record(job, result);
      LOG.atInfo()
          .addKeyValue("event", "payment.approval")
          .addKeyValue("paymentId", job.paymentId())
          .addKeyValue("jobId", job.jobId())
          .addKeyValue("attempt", job.attempts())
          .addKeyValue("result", result.getClass().getSimpleName())
          .log("Approval job result recorded");
    } catch (ApprovalLeaseLostException e) {
      LOG.atWarn()
          .addKeyValue("event", "payment.approval")
          .addKeyValue("paymentId", job.paymentId())
          .addKeyValue("jobId", job.jobId())
          .addKeyValue("result", "LEASE_LOST")
          .log("Approval result was not recorded because the lease was lost");
    }
    return true;
  }

  private Result call(LeasedJob job, ApprovalResultRecorder.Prepared prepared) {
    if (prepared.firstCall()) {
      return gateway.approve(prepared.request());
    }
    Result found = gateway.lookup(prepared.request());
    if (found instanceof Result.NotFound
        && jobs.holdsLease(job.jobId(), job.leaseToken(), settings.httpTimeout())) {
      return gateway.approve(prepared.request());
    }
    return found;
  }
}
