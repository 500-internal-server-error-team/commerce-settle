package com.commercesettle.settlement.domain.repository;

import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 지급 작업 저장과 임대. 임대·만료 판정은 DB 시각으로 하고, 외부 호출 동안 DB 잠금을 쥐지 않는다. */
public interface PayoutJobRepository {
  PayoutJob save(PayoutJob job);

  Optional<PayoutJob> findByPayoutId(UUID payoutId);

  List<PayoutJob> findByPayoutIds(Collection<UUID> payoutIds);

  Optional<PayoutJob> findById(UUID id);

  /** 행을 잠그고 읽는다. 잠금 동안 Worker의 임대 선점은 이 작업을 건너뛴다. 호출자의 트랜잭션에서 실행한다. */
  Optional<PayoutJob> findByIdForUpdate(UUID id);

  /** 변경을 즉시 반영해 증가한 버전을 응답에 쓸 수 있게 한다. */
  PayoutJob saveNow(PayoutJob job);

  /** 작업을 생성 시각·ID 내림차순으로 한 페이지 읽는다. */
  List<PayoutJob> findPage(int page, int size);

  long count();

  /** 운영 재처리 이력. 같은 작업·같은 예상 버전의 재처리는 한 번뿐이다. */
  void recordReplay(
      UUID jobId,
      long expectedVersion,
      PayoutJobStatus previousStatus,
      int previousAttempts,
      String operatorId,
      String reason,
      Instant now);

  /**
   * 도래한 PENDING·RETRY 작업이나 임대가 만료된 RUNNING 작업 하나를 RUNNING으로 바꾸고 새 임대 token을 준다. 시도 횟수를 하나 늘리며, 만료
   * 회수도 시도에 포함한다. 다른 실행자가 잠근 작업은 건너뛴다.
   */
  Optional<LeasedJob> leaseNext(String owner, Duration leaseDuration);

  /** {@link #leaseNext(String, Duration)}와 같되 {@code kind}에 해당하는 작업만 고른다. */
  Optional<LeasedJob> leaseNext(String owner, Duration leaseDuration, LeaseKind kind);

  /** 이 token의 임대가 {@code minRemaining}보다 더 남아 있는지 확인한다. 외부 호출 직전에 호출 제한 시간을 넘겨 쓴다. */
  boolean holdsLease(UUID jobId, UUID leaseToken, Duration minRemaining);

  /**
   * 유효한 임대를 가진 경우에만 작업을 끝내거나 다시 대기시키고 임대를 비운다. 다음 실행 시각은 DB 시각에 {@code runAfter}를 더해 정한다. 호출자의
   * 트랜잭션에서 실행하며, false면 다른 실행자가 인계했거나 임대가 끝난 것이라 같은 트랜잭션의 결과 반영을 모두 롤백해야 한다.
   */
  boolean releaseLease(UUID jobId, UUID leaseToken, PayoutJobStatus status, Duration runAfter);

  /** 동시 실행 슬롯을 나누는 작업 종류. 작업 상태로 구분한다. */
  enum LeaseKind {
    /** 도래한 PENDING. 아직 실행 요청을 보낸 적 없는 새 지급이다. */
    NEW,
    /** 도래한 RETRY(결과 조회)와 임대가 만료된 RUNNING(중단 복구). */
    RECOVERY
  }

  record LeasedJob(
      UUID jobId,
      UUID payoutId,
      UUID businessKey,
      UUID leaseToken,
      int attempts,
      String traceParent,
      Timing timing) {
    public LeasedJob(UUID jobId, UUID payoutId, UUID businessKey, UUID leaseToken, int attempts) {
      this(jobId, payoutId, businessKey, leaseToken, attempts, null, null);
    }
  }
}
