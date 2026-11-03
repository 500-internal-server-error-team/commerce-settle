package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.port.OperatorRequestStore;
import com.commercesettle.payment.application.port.OperatorRequestStore.Audit;
import com.commercesettle.payment.application.port.OperatorRequestStore.Scope;
import com.commercesettle.payment.application.port.QuarantineStore;
import com.commercesettle.payment.application.port.QuarantineStore.DeliveryView;
import com.commercesettle.payment.application.port.QuarantineStore.QuarantineView;
import com.commercesettle.payment.domain.exception.IdempotencyConflictException;
import com.commercesettle.payment.domain.exception.QuarantineNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자의 격리 원문 조회와 원키 재처리·소진 발행 재개(docs/api.md#payment-격리-원문-운영). 상태 전이는 {@link QuarantineService}가
 * 맡고, 여기서는 멱등 기록과 감사를 같은 트랜잭션에 묶는다. 발행 접수는 Kafka 발행 완료나 업무 반영 성공이 아니다.
 *
 * <p>같은 운영자·작업·키의 요청은 {@link OperatorRequestStore#findForUpdate}로 트랜잭션 끝까지 직렬화하므로, 같은 키·본문 재요청은 동시
 * 호출에서도 한 번만 반영되고 처음 결과의 현재 상태를 돌려준다. 키와 본문 원문은 저장하지 않는다.
 */
@Service
public class QuarantineOperationService {
  static final String REPLAY = "QUARANTINE_REPLAY";
  static final String RESUME = "QUARANTINE_DELIVERY_RESUME";
  private static final String ACTOR_KIND = "OPERATOR";
  private static final Set<String> DELIVERY_STATUSES =
      Set.of("PENDING", "RUNNING", "RETRY", "PUBLISHED", "REVIEW_REQUIRED");

  private final QuarantineService quarantines;
  private final QuarantineStore store;
  private final OperatorRequestStore requests;
  private final Clock clock;

  public QuarantineOperationService(
      QuarantineService quarantines,
      QuarantineStore store,
      OperatorRequestStore requests,
      Clock clock) {
    this.quarantines = quarantines;
    this.store = store;
    this.requests = requests;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public QuarantinePage list(String deliveryStatus, int page, int size) {
    if (page < 0
        || size < 1
        || size > 100
        || (deliveryStatus != null && !DELIVERY_STATUSES.contains(deliveryStatus))) {
      throw new IllegalArgumentException("Invalid quarantine page");
    }
    long total = store.count(deliveryStatus);
    // 시작 위치가 int를 넘는 페이지는 행이 있을 수 없으므로 조회하지 않는다.
    List<QuarantineView> items =
        (long) page * size > Integer.MAX_VALUE
            ? List.of()
            : store.findPage(deliveryStatus, page, size);
    return new QuarantinePage(items, page, size, total);
  }

  @Transactional(readOnly = true)
  public QuarantineView get(UUID quarantineId) {
    return store.find(quarantineId).orElseThrow(QuarantineNotFoundException::new);
  }

  /** 같은 키·본문의 재요청은 원문의 현재 상태를 돌려주고 REPLAY를 다시 만들지 않는다. */
  @Transactional
  public QuarantineView replay(
      String operator, UUID quarantineId, String key, long expectedVersion, String reasonCode) {
    return idempotent(
        operator,
        REPLAY,
        key,
        List.of(quarantineId, expectedVersion, reasonCode),
        () -> {
          UUID delivery =
              quarantines.requestReplay(quarantineId, expectedVersion, operator, reasonCode);
          audit(operator, "QUARANTINE_REPLAY_REQUESTED", delivery, null, reasonCode, quarantineId);
          return get(quarantineId);
        },
        QuarantineView::id,
        this::get);
  }

  /** 같은 키·본문의 재요청은 작업의 현재 상태를 돌려주고 다시 재개하지 않는다. */
  @Transactional
  public DeliveryView resume(
      String operator, UUID deliveryId, String key, long expectedVersion, String reasonCode) {
    return idempotent(
        operator,
        RESUME,
        key,
        List.of(deliveryId, expectedVersion, reasonCode),
        () -> {
          quarantines.resumeDelivery(deliveryId, expectedVersion, operator, reasonCode);
          DeliveryView resumed = delivery(deliveryId);
          audit(
              operator,
              "QUARANTINE_DELIVERY_RESUMED",
              deliveryId,
              "REVIEW_REQUIRED",
              reasonCode,
              resumed.quarantineId());
          return resumed;
        },
        DeliveryView::id,
        this::delivery);
  }

  /**
   * 같은 키의 완료 기록이 있으면 본문 해시를 비교해 {@code replay}로 현재 상태를 돌려준다. 없으면 {@code firstRun} 결과의 리소스로 완료 기록을
   * 남긴다.
   */
  private <T> T idempotent(
      String operator,
      String operation,
      String key,
      List<?> body,
      Supplier<T> firstRun,
      Function<T, UUID> resourceOf,
      Function<UUID, T> replay) {
    if (key == null || key.length() < 8 || key.length() > 128) {
      throw new IllegalArgumentException("Invalid idempotency key");
    }
    Scope scope = new Scope(ACTOR_KIND, operator, operation, sha256(key));
    String requestHash = sha256(fingerprint(body));
    Optional<OperatorRequestStore.Completed> existing = requests.findForUpdate(scope);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(requestHash)) {
        throw new IdempotencyConflictException();
      }
      return replay.apply(existing.get().resourceId());
    }
    T result = firstRun.get();
    requests.saveCompleted(
        UUID.randomUUID(), scope, requestHash, resourceOf.apply(result), 202, clock.instant());
    return result;
  }

  private DeliveryView delivery(UUID deliveryId) {
    return store.findDelivery(deliveryId).orElseThrow(QuarantineNotFoundException::new);
  }

  /** 같은 원문의 재처리·재개 감사를 묶어 볼 수 있게 격리 ID로 추적한다. */
  private void audit(
      String operator,
      String action,
      UUID deliveryId,
      String beforeStatus,
      String reasonCode,
      UUID quarantineId) {
    Instant now = clock.instant();
    requests.audit(
        new Audit(
            ACTOR_KIND,
            operator,
            action,
            "INBOX_QUARANTINE_DELIVERY",
            deliveryId,
            beforeStatus,
            "PENDING",
            reasonCode,
            quarantineId.toString(),
            now));
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

  /** 운영 목록 페이지. */
  public record QuarantinePage(List<QuarantineView> items, int page, int size, long totalElements) {
    public QuarantinePage {
      items = List.copyOf(items);
    }

    public int totalPages() {
      return (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
    }
  }
}
