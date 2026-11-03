package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.application.dto.PaymentJobView;
import com.commercesettle.payment.application.port.OperatorRequestStore;
import com.commercesettle.payment.application.port.OperatorRequestStore.Audit;
import com.commercesettle.payment.application.port.OperatorRequestStore.Scope;
import com.commercesettle.payment.domain.exception.IdempotencyConflictException;
import com.commercesettle.payment.domain.exception.PaymentJobNotFoundException;
import com.commercesettle.payment.domain.exception.PaymentStateConflictException;
import com.commercesettle.payment.domain.exception.VersionConflictException;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.ReplayResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자의 결제 작업 조회와 원업무 재처리. 재처리는 같은 작업 행의 원업무 키·금액·결제수단과 고정한 승인 시도를 그대로 쓰므로 새 금융 거래를 만들지 않는다. 미확정 시도는
 * 다음 실행에서 원거래 조회부터 재개한다.
 *
 * <p>재처리·멱등 기록·감사 행은 한 트랜잭션에 저장한다. 같은 키·본문의 재요청은 작업을 다시 바꾸지 않고 현재 작업을 돌려준다. 키와 본문 원문은 저장하지 않는다.
 */
@Service
public class PaymentJobOperationService {
  static final String OPERATION = "REPLAY_PAYMENT_JOB";
  private static final String ACTOR_KIND = "OPERATOR";

  private final PaymentJobRepository jobs;
  private final OperatorRequestStore requests;
  private final Clock clock;

  public PaymentJobOperationService(
      PaymentJobRepository jobs, OperatorRequestStore requests, Clock clock) {
    this.jobs = jobs;
    this.requests = requests;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageView<PaymentJobView> list(int page, int size) {
    return PageView.read(
        page,
        size,
        jobs.count(),
        (p, s) -> jobs.findPage(p, s).stream().map(PaymentJobView::of).toList());
  }

  /**
   * @param operator 내부 호출 필터가 확인한 운영자 발신자 ID
   * @param reasonCode 재처리 사유 코드. 감사 기록에 남는다
   */
  @Transactional
  public PaymentJobView replay(
      String operator, UUID jobId, String key, long expectedVersion, String reasonCode) {
    Scope scope = new Scope(ACTOR_KIND, operator, OPERATION, sha256(key));
    String requestHash = sha256(fingerprint(List.of(jobId, expectedVersion, reasonCode)));
    Optional<OperatorRequestStore.Completed> existing = requests.findForUpdate(scope);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(requestHash)) {
        throw new IdempotencyConflictException();
      }
      return view(existing.get().resourceId());
    }

    PaymentJobStatus before =
        switch (jobs.replay(jobId, expectedVersion)) {
          case ReplayResult.Replayed replayed -> replayed.before();
          case ReplayResult.NotFound ignored -> throw new PaymentJobNotFoundException();
          case ReplayResult.VersionConflict ignored -> throw new VersionConflictException();
          case ReplayResult.StateConflict conflict ->
              throw new PaymentStateConflictException(
                  conflict.current() == PaymentJobStatus.DONE
                      ? "완료한 작업은 재처리할 수 없습니다."
                      : "실행 중인 작업은 재처리할 수 없습니다.");
        };

    UUID recordId = UUID.randomUUID();
    var now = clock.instant();
    requests.saveCompleted(recordId, scope, requestHash, jobId, 202, now);
    requests.audit(
        new Audit(
            ACTOR_KIND,
            operator,
            "PAYMENT_JOB_REPLAYED",
            "PAYMENT_JOB",
            jobId,
            before.name(),
            PaymentJobStatus.PENDING.name(),
            reasonCode,
            recordId.toString(),
            now));
    return view(jobId);
  }

  private PaymentJobView view(UUID jobId) {
    return jobs.findById(jobId)
        .map(PaymentJobView::of)
        .orElseThrow(PaymentJobNotFoundException::new);
  }

  /** 길이를 앞에 붙여 값의 경계가 섞이지 않게 한다. */
  private static String fingerprint(List<?> values) {
    StringBuilder text = new StringBuilder();
    for (Object value : values) {
      String part = String.valueOf(value);
      text.append(part.length()).append(':').append(part).append(';');
    }
    return text.toString();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
