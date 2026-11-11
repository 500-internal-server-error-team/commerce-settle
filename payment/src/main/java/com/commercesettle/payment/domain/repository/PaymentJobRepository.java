package com.commercesettle.payment.domain.repository;

import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 결제 작업 저장과 임대. 임대·만료 판정은 DB 시각으로 하고, 외부 호출 동안 DB 잠금을 쥐지 않는다. */
public interface PaymentJobRepository {
  PaymentJob save(PaymentJob job);

  Optional<PaymentJob> findByOperationAndBusinessKey(
      PaymentJobOperation operation, UUID businessKey);

  /**
   * 도래한 PENDING·RETRY 작업이나 임대가 만료된 RUNNING 작업 하나를 RUNNING으로 바꾸고 새 임대 token을 준다. 시도 횟수를 하나 늘리며, 만료
   * 회수도 시도에 포함한다. 다른 실행자가 잠근 작업은 건너뛴다.
   */
  Optional<LeasedJob> leaseNext(
      PaymentJobOperation operation, String owner, Duration leaseDuration);

  /** 이 token의 임대가 {@code minRemaining}보다 더 남아 있는지 확인한다. 외부 호출 직전에 호출 제한 시간을 넘겨 쓴다. */
  boolean holdsLease(UUID jobId, UUID leaseToken, Duration minRemaining);

  /**
   * 유효한 임대를 가진 경우에만 작업을 끝내거나 다시 대기시키고 임대를 비운다. 호출자의 트랜잭션에서 실행하며, false면 다른 실행자가 인계했거나 임대가 끝난 것이라 같은
   * 트랜잭션의 결과 반영을 모두 롤백해야 한다.
   */
  boolean releaseLease(
      UUID jobId, UUID leaseToken, PaymentJobStatus status, Duration runAfter, String errorCode);

  Optional<PaymentJob> findById(UUID jobId);

  /** 작업을 생성 시각·ID 내림차순으로 한 페이지 읽는다. */
  List<PaymentJob> findPage(int page, int size);

  long count();

  /**
   * 운영 재처리로 원업무 키를 유지한 채 작업을 즉시 다시 대기시키고 자동 시도 횟수를 새로 센다. 완료한 작업과 유효한 임대 중인 작업은 바꾸지 않는다. 판정과 변경은 작업
   * 행 잠금 안에서 DB 시각으로 한다.
   */
  ReplayResult replay(UUID jobId, long expectedVersion);

  sealed interface ReplayResult {
    record Replayed(PaymentJobStatus before) implements ReplayResult {}

    record NotFound() implements ReplayResult {}

    record VersionConflict() implements ReplayResult {}

    /** 완료했거나 다른 실행자가 유효한 임대를 가진 작업. */
    record StateConflict(PaymentJobStatus current) implements ReplayResult {}
  }

  record LeasedJob(
      UUID jobId,
      UUID paymentId,
      UUID businessKey,
      UUID leaseToken,
      int attempts,
      Instant registeredAt,
      String traceParent,
      Timing timing) {
    public LeasedJob(
        UUID jobId,
        UUID paymentId,
        UUID businessKey,
        UUID leaseToken,
        int attempts,
        Instant registeredAt) {
      this(jobId, paymentId, businessKey, leaseToken, attempts, registeredAt, null, null);
    }
  }
}
