package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.DailySettlementSettings;
import com.commercesettle.settlement.application.exception.FixtureBatchConflictException;
import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.domain.exception.PayoutNotAllowedException;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutRecipient;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItemStatus;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository.ReservedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자 한 명의 일배치. 커머스 지급 컨텍스트는 DB 트랜잭션 밖에서 조회한다. 지급 선점·저장은 판매자 단위 트랜잭션으로 처리하고, 선점 시 현재 상태와 검증한 상품 집합을
 * 다시 확인한다. 대상 선점·지급 예약·합계·배치·지급·지급 작업과 정산 확정 알림 요청은 그 한 트랜잭션에 고정하며, 지급 전 검증에 실패하면 선점까지 롤백해 항목이
 * PAYABLE로 남고 다음 실행에서 다시 고려된다.
 */
@Service
public class SellerSettlementService {
  private final SettlementItemRepository items;
  private final SettlementBatchRepository batches;
  private final PayoutRepository payouts;
  private final PayoutJobRepository payoutJobs;
  private final ProductSettlementContextReader contexts;
  private final DailySettlementSettings settings;
  private final SettlementNoticeService notices;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final TransactionTemplate reads;

  public SellerSettlementService(
      SettlementItemRepository items,
      SettlementBatchRepository batches,
      PayoutRepository payouts,
      PayoutJobRepository payoutJobs,
      ProductSettlementContextReader contexts,
      DailySettlementSettings settings,
      SettlementNoticeService notices,
      Clock clock,
      PlatformTransactionManager manager) {
    this.items = items;
    this.batches = batches;
    this.payouts = payouts;
    this.payoutJobs = payoutJobs;
    this.contexts = contexts;
    this.settings = settings;
    this.notices = notices;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
    this.reads = new TransactionTemplate(manager);
    this.reads.setReadOnly(true);
  }

  /**
   * 같은 판매자·날짜·정책 버전의 배치가 있으면 대상을 다시 고르지 않고 그 배치를 돌려준다. 대상이 없으면 배치를 만들지 않는다. 호출자는 DB 트랜잭션을 열지 않고
   * 호출해야 한다.
   *
   * @throws PayoutNotAllowedException 지급 전 검증에 실패하면
   */
  public Optional<SettlementBatch> settle(UUID sellerId, LocalDate batchDate) {
    return settlePrepared(prepare(sellerId, batchDate, null)).map(Settled::batch);
  }

  /** 개발 시나리오의 지정 항목만 허용한다. 동시 유입된 일반 항목을 선점했으면 해당 판매자를 롤백한다. */
  public Optional<SettlementBatch> settleFixture(
      UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    return settlePrepared(prepare(sellerId, batchDate, Objects.requireNonNull(itemIds)))
        .map(Settled::batch);
  }

  /**
   * 운영 요청의 조회 단계. 기존 배치 확인·대상 상품 조회는 짧은 읽기 트랜잭션에서, 커머스 지급 컨텍스트는 DB 트랜잭션 밖에서 읽는다. 결과는 {@link
   * #settlePrepared}에 넘긴다.
   *
   * @throws PayoutNotAllowedException 지급 전 검증에 실패하면
   */
  public Prepared prepare(UUID sellerId, LocalDate batchDate) {
    return prepare(sellerId, batchDate, null);
  }

  /**
   * 조회 단계 결과로 선점·저장한다. 호출자의 트랜잭션이 있으면 거기에 참여하므로 운영 요청은 멱등 기록·감사를 같은 트랜잭션에 남길 수 있다. 돌려준 배치를 이번 호출이
   * 만들었는지도 알려 준다. 기존 배치를 받은 호출은 생성 감사를 남기지 않는다.
   *
   * @throws PayoutNotAllowedException 조회와 선점 사이에 검증하지 않은 상품의 항목이 준비됐으면
   */
  public Optional<Settled> settlePrepared(Prepared prepared) {
    if (prepared.existing() != null) {
      return Optional.of(new Settled(prepared.existing(), false));
    }
    if (prepared.productIds().isEmpty()) {
      return Optional.empty();
    }
    return transactions.execute(status -> reserve(prepared));
  }

  /**
   * @param created 이번 호출이 배치를 만들었으면 true, 같은 판매자·날짜·정책의 기존 배치면 false
   */
  public record Settled(SettlementBatch batch, boolean created) {}

  /**
   * 조회 단계 결과. 기존 배치가 있으면 {@code existing}만, 대상이 없으면 빈 {@code productIds}를 담는다.
   *
   * @param itemIds 개발 시나리오의 지정 항목. 일반 실행은 null
   */
  public record Prepared(
      UUID sellerId,
      LocalDate batchDate,
      Set<UUID> itemIds,
      SettlementBatch existing,
      Set<UUID> productIds,
      PayoutRecipient recipient,
      com.commercesettle.settlement.application.port.DemoSettlementStore.Run demoRun) {}

  public Optional<SettlementBatch> settleDemo(
      UUID sellerId, com.commercesettle.settlement.application.port.DemoSettlementStore.Run run) {
    if (!run.sellerId().equals(sellerId))
      throw new IllegalArgumentException("Demo seller mismatch");
    var existing = reads.execute(status -> batches.findDemo(sellerId, run.id()));
    var productIds =
        reads.execute(
            status -> items.findDemoPayableProductIds(sellerId, run.cutoffAt(), run.itemIds()));
    if (existing.isPresent()) {
      if (!productIds.isEmpty())
        throw new com.commercesettle.settlement.application.exception.DemoRunException(
            "DEMO_SCOPE_CONFLICT", "기존 배치와 미처리 대상이 일치하지 않습니다.");
      return existing;
    }
    if (productIds.isEmpty())
      throw new com.commercesettle.settlement.application.exception.DemoRunException(
          "DEMO_NOT_READY", "시연 대상이 더 이상 지급 가능하지 않습니다.");
    var recipient =
        PayoutRecipient.resolve(sellerId, productIds, contexts.read(sellerId, productIds));
    return settlePrepared(
            new Prepared(
                sellerId,
                run.cutoffAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),
                run.itemIds(),
                null,
                productIds,
                recipient,
                run))
        .map(Settled::batch);
  }

  private Prepared prepare(UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    Optional<SettlementBatch> existing =
        reads.execute(status -> existingBatch(sellerId, batchDate, itemIds));
    if (existing.isPresent()) {
      return new Prepared(sellerId, batchDate, itemIds, existing.get(), Set.of(), null, null);
    }
    Instant cutoffAt = SettlementBatch.cutoffOf(batchDate);
    Set<UUID> productIds = reads.execute(status -> items.findPayableProductIds(sellerId, cutoffAt));
    if (productIds.isEmpty()) {
      return new Prepared(sellerId, batchDate, itemIds, null, Set.of(), null, null);
    }
    PayoutRecipient recipient =
        PayoutRecipient.resolve(sellerId, productIds, contexts.read(sellerId, productIds));
    return new Prepared(sellerId, batchDate, itemIds, null, productIds, recipient, null);
  }

  private Optional<SettlementBatch> existingBatch(
      UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    Optional<SettlementBatch> existing =
        batches.find(sellerId, batchDate, settings.policyVersion());
    if (existing.isPresent()
        && itemIds != null
        && itemIds.stream()
            .map(items::findById)
            .flatMap(Optional::stream)
            .anyMatch(
                item ->
                    item.getSellerId().equals(sellerId)
                        && item.getStatus() == SettlementItemStatus.PAYABLE
                        && item.getPayoutId() == null
                        && item.getReadyAt().isBefore(SettlementBatch.cutoffOf(batchDate)))) {
      throw new FixtureBatchConflictException(
          "Existing settlement batch conflicts with unprocessed fixture items");
    }
    return existing;
  }

  private Optional<Settled> reserve(Prepared prepared) {
    UUID sellerId = prepared.sellerId();
    LocalDate batchDate = prepared.batchDate();
    Set<UUID> itemIds = prepared.itemIds();
    Set<UUID> productIds = prepared.productIds();
    // 조회하는 동안 다른 실행이 같은 배치를 만들었으면 그 배치를 돌려준다.
    Optional<SettlementBatch> existing =
        prepared.demoRun() == null
            ? existingBatch(sellerId, batchDate, itemIds)
            : batches.findDemo(sellerId, prepared.demoRun().id());
    if (existing.isPresent()) {
      return Optional.of(new Settled(existing.get(), false));
    }
    Instant cutoffAt =
        prepared.demoRun() == null
            ? SettlementBatch.cutoffOf(batchDate)
            : prepared.demoRun().cutoffAt();
    Instant now = clock.instant();
    UUID payoutId = UUID.randomUUID();
    List<ReservedItem> reserved =
        prepared.demoRun() == null
            ? items.reserveForPayout(sellerId, cutoffAt, payoutId, now)
            : items.reserveDemoForPayout(sellerId, cutoffAt, payoutId, now, itemIds);
    if (prepared.demoRun() != null && reserved.size() != itemIds.size())
      throw new com.commercesettle.settlement.application.exception.DemoRunException(
          "DEMO_NOT_READY", "시연 대상의 상태가 변경됐습니다.");
    if (reserved.isEmpty()) {
      return Optional.empty();
    }
    if (itemIds != null && reserved.stream().anyMatch(item -> !itemIds.contains(item.id()))) {
      throw new FixtureBatchConflictException("Fixture batch would reserve a non-fixture item");
    }
    // 조회와 선점 사이에 새 상품의 항목이 준비됐으면 검증하지 않은 상품이라 이번 실행에서 지급하지 않는다.
    if (reserved.stream().anyMatch(item -> !productIds.contains(item.productId()))) {
      throw new PayoutNotAllowedException("an unverified product was reserved");
    }
    long netAmount = reserved.stream().mapToLong(ReservedItem::netAmount).sum();

    SettlementBatch batch =
        batches.save(
            prepared.demoRun() == null
                ? SettlementBatch.reserve(
                    sellerId, batchDate, settings.policyVersion(), netAmount, now)
                : SettlementBatch.reserveDemo(
                    prepared.demoRun().id(), sellerId, cutoffAt, netAmount, now));
    Payout payout =
        payouts.save(
            Payout.create(
                payoutId,
                batch.getId(),
                sellerId,
                prepared.recipient(),
                settings.fundingAccountRef(),
                settings.payoutScenarioVersion(),
                netAmount,
                now));
    payoutJobs.save(PayoutJob.schedule(payout, now));
    notices.ready(batch, payout, now);
    return Optional.of(new Settled(batch, true));
  }
}
