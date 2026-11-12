package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.PaymentProcessingObservation;
import com.commercesettle.payment.application.port.PaymentProcessingObservation.Stage;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.application.port.RefundGateway.RefundRequest;
import com.commercesettle.payment.application.service.RefundResultRecorder.RefundLeaseLostException;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 환불 Worker. 정산금 예약이 확인된 환불 작업 하나를 임대해 원외부 거래에 외부 결제액만 환불하고, 성공을 확인하면 사용 마일리지를 복원한다. 처음 호출하는 환불만 실행
 * 요청을 보내고, 결과를 모르는 환불은 원거래 조회부터 한다. 외부 호출 직전에는 임대에 호출 제한 시간만큼 남았는지 확인한다. 조회가 거래 부재(404)면 임대에 호출 제한
 * 시간만큼 남았는지 다시 확인한 뒤 같은 업무 키·본문으로 재전송한다.
 */
@Service
public class RefundWorker {
  private static final Logger LOG = LoggerFactory.getLogger(RefundWorker.class);

  private final PaymentJobRepository jobs;
  private final RefundResultRecorder recorder;
  private final RefundGateway gateway;
  private final RefundWorkerSettings settings;
  private final PaymentProcessingObservation observations;
  private final String owner = "payment-refund-" + UUID.randomUUID();

  RefundWorker(
      PaymentJobRepository jobs,
      RefundResultRecorder recorder,
      RefundGateway gateway,
      RefundWorkerSettings settings) {
    this(jobs, recorder, gateway, settings, PaymentProcessingObservation.NOOP);
  }

  @Autowired
  RefundWorker(
      PaymentJobRepository jobs,
      RefundResultRecorder recorder,
      RefundGateway gateway,
      RefundWorkerSettings settings,
      PaymentProcessingObservation observations) {
    this.jobs = jobs;
    this.recorder = recorder;
    this.gateway = gateway;
    this.settings = settings;
    this.observations = observations;
  }

  /** 도래한 작업이 없으면 false를 돌려준다. */
  public boolean runOnce() {
    Optional<LeasedJob> leased =
        observations.observe(
            Stage.REFUND_LEASE,
            () -> jobs.leaseNext(PaymentJobOperation.REFUND, owner, settings.leaseDuration()));
    if (leased.isEmpty()) {
      return false;
    }
    LeasedJob job = leased.get();
    return observations.observeStored(
        Stage.REFUND_PROCESS,
        job.traceParent(),
        Map.of("job.id", job.jobId().toString(), "payment.id", job.paymentId().toString()),
        job.timing(),
        () -> processObserved(job));
  }

  private boolean processObserved(LeasedJob job) {
    Optional<RefundResultRecorder.Prepared> prepared =
        observations.observe(Stage.REFUND_PREPARE, () -> recorder.prepare(job));
    if (prepared.isEmpty()) {
      return true;
    }
    Optional<RefundRequest> request = prepared.get().request();
    // 준비 트랜잭션 커밋까지 걸린 시간만큼 임대가 줄었으므로 외부 호출 직전에 남은 임대를 다시 확인한다.
    if (request.isPresent()
        && !observations.observe(
            Stage.REFUND_LEASE_CHECK,
            () -> jobs.holdsLease(job.jobId(), job.leaseToken(), settings.httpTimeout()))) {
      logLeaseLost(job, "Refund was not called because the lease was lost");
      return true;
    }
    Optional<Result> result =
        observations.observe(
            Stage.REFUND_GATEWAY, () -> request.map(r -> call(job, r, prepared.get().firstCall())));
    try {
      observations.observe(Stage.REFUND_RECORD, () -> recorder.record(job, result));
    } catch (RefundLeaseLostException e) {
      logLeaseLost(job, "Refund result was not recorded because the lease was lost");
    }
    return true;
  }

  private static void logLeaseLost(LeasedJob job, String message) {
    LOG.atDebug()
        .addKeyValue("event", "payment.refund")
        .addKeyValue("paymentId", job.paymentId())
        .addKeyValue("jobId", job.jobId())
        .addKeyValue("result", "LEASE_LOST")
        .log(message);
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
