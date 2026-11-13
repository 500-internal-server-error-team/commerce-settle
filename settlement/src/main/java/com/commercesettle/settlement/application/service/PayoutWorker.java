package com.commercesettle.settlement.application.service;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.exception.LeaseLostException;
import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.application.port.PayoutGateway.Result;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeaseKind;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeasedJob;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 지급 Worker. 도래한 지급 작업 하나를 임대해 대역을 호출하고 결과를 반영한다. 처음 실행하는 지급만 실행 요청을 보내고, 결과를 모르는 지급은 원거래 조회부터 한다.
 * 조회가 거래 부재(404)면 임대에 호출 제한 시간만큼 남았는지 다시 확인한 뒤 같은 업무 키·본문으로 재전송한다.
 */
@Service
public class PayoutWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(PayoutWorker.class);

  private final PayoutJobRepository jobs;
  private AsyncObservation observations = AsyncObservation.NOOP;
  private final PayoutResultRecorder recorder;
  private final PayoutGateway gateway;
  private final PayoutWorkerSettings settings;
  private final String owner = "settlement-" + UUID.randomUUID();

  PayoutWorker(
      PayoutJobRepository jobs,
      PayoutResultRecorder recorder,
      PayoutGateway gateway,
      PayoutWorkerSettings settings) {
    this.jobs = jobs;
    this.recorder = recorder;
    this.gateway = gateway;
    this.settings = settings;
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  /** 종류와 관계없이 도래한 작업 하나를 임대해 실행한다. 도래한 작업이 없으면 false를 돌려준다. */
  public boolean runOnce() {
    Optional<LeasedJob> leased = jobs.leaseNext(owner, settings.leaseDuration());
    leased.ifPresent(this::run);
    return leased.isPresent();
  }

  /** 슬롯 종류에 맞는 도래한 작업 하나를 임대한다. 실행은 {@link #run(LeasedJob)}이 한다. */
  public Optional<LeasedJob> lease(LeaseKind kind) {
    return jobs.leaseNext(owner, settings.leaseDuration(), kind);
  }

  /** 임대한 작업을 실행하고 결과를 반영한다. 임대를 잃었으면 결과를 버린다. */
  public void run(LeasedJob job) {
    observations.run(
        "settlement.payout.process",
        job.traceParent(),
        Map.of("job.id", job.jobId().toString(), "payout.id", job.payoutId().toString()),
        job.timing(),
        () -> runObserved(job));
  }

  private void runObserved(LeasedJob job) {
    Optional<PayoutResultRecorder.Prepared> prepared =
        observations.step("settlement.payout.prepare", () -> recorder.prepare(job));
    if (prepared.isEmpty()) {
      return;
    }
    Result result = observations.step("settlement.payout.gateway", () -> call(job, prepared.get()));
    try {
      observations.step("settlement.payout.record", () -> recorder.record(job, result));
      // 성공만 DEBUG다. 거절·미확정·부재는 확인 대상이나 미확정으로 남으므로 WARN이다.
      (result instanceof Result.Succeeded ? LOGGER.atDebug() : LOGGER.atWarn())
          .addKeyValue("event", "settlement.payout")
          .addKeyValue("payoutId", job.payoutId())
          .addKeyValue("jobId", job.jobId())
          .addKeyValue("attempt", job.attempts())
          .addKeyValue("result", result.getClass().getSimpleName())
          .log("Payout job result recorded");
    } catch (LeaseLostException e) {
      LOGGER
          .atDebug()
          .addKeyValue("event", "settlement.payout")
          .addKeyValue("payoutId", job.payoutId())
          .addKeyValue("jobId", job.jobId())
          .addKeyValue("result", "LEASE_LOST")
          .log("Payout result was not recorded because the lease was lost");
    }
  }

  private Result call(LeasedJob job, PayoutResultRecorder.Prepared prepared) {
    if (prepared.firstCall()) {
      return gateway.execute(prepared.request());
    }
    Result found = gateway.lookup(prepared.request());
    if (found instanceof Result.NotFound
        && jobs.holdsLease(job.jobId(), job.leaseToken(), settings.httpTimeout())) {
      return gateway.execute(prepared.request());
    }
    return found;
  }
}
