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
 * 다시 확인한다. 대상 선점·지급 예약·합계·배치·지급·지급 작업은 그 한 트랜잭션에 고정하며, 지급 전 검증에 실패하면 선점까지 롤백해 항목이 PAYABLE로 남고 다음
 * 실행에서 다시 고려된다. 호출자는 DB 트랜잭션을 열지 않고 호출해야 한다.
 */
@Service
public class SellerSettlementService {
  private final SettlementItemRepository items;
  private final SettlementBatchRepository batches;
  private final PayoutRepository payouts;
  private final PayoutJobRepository payoutJobs;
  private final ProductSettlementContextReader contexts;
  private final DailySettlementSettings settings;
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
      Clock clock,
      PlatformTransactionManager manager) {
    this.items = items;
    this.batches = batches;
    this.payouts = payouts;
    this.payoutJobs = payoutJobs;
    this.contexts = contexts;
    this.settings = settings;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
    this.reads = new TransactionTemplate(manager);
    this.reads.setReadOnly(true);
  }

  /**
   * 같은 판매자·날짜·정책 버전의 배치가 있으면 대상을 다시 고르지 않고 그 배치를 돌려준다. 대상이 없으면 배치를 만들지 않는다.
   *
   * @throws PayoutNotAllowedException 지급 전 검증에 실패하면
   */
  public Optional<SettlementBatch> settle(UUID sellerId, LocalDate batchDate) {
    return settleSelected(sellerId, batchDate, null);
  }

  /** 개발 시나리오의 지정 항목만 허용한다. 동시 유입된 일반 항목을 선점했으면 해당 판매자를 롤백한다. */
  public Optional<SettlementBatch> settleFixture(
      UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    return settleSelected(sellerId, batchDate, Objects.requireNonNull(itemIds));
  }

  private Optional<SettlementBatch> settleSelected(
      UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    Optional<SettlementBatch> existing =
        reads.execute(status -> existingBatch(sellerId, batchDate, itemIds));
    if (existing.isPresent()) {
      return existing;
    }
    Instant cutoffAt = SettlementBatch.cutoffOf(batchDate);
    Set<UUID> productIds = reads.execute(status -> items.findPayableProductIds(sellerId, cutoffAt));
    if (productIds.isEmpty()) {
      return Optional.empty();
    }
    PayoutRecipient recipient =
        PayoutRecipient.resolve(sellerId, productIds, contexts.read(sellerId, productIds));
    return transactions.execute(
        status -> reserve(sellerId, batchDate, itemIds, cutoffAt, productIds, recipient));
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

  private Optional<SettlementBatch> reserve(
      UUID sellerId,
      LocalDate batchDate,
      Set<UUID> itemIds,
      Instant cutoffAt,
      Set<UUID> productIds,
      PayoutRecipient recipient) {
    // 조회하는 동안 다른 실행이 같은 배치를 만들었으면 그 배치를 돌려준다.
    Optional<SettlementBatch> existing = existingBatch(sellerId, batchDate, itemIds);
    if (existing.isPresent()) {
      return existing;
    }
    Instant now = clock.instant();
    UUID payoutId = UUID.randomUUID();
    List<ReservedItem> reserved = items.reserveForPayout(sellerId, cutoffAt, payoutId, now);
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
            SettlementBatch.reserve(sellerId, batchDate, settings.policyVersion(), netAmount, now));
    Payout payout =
        payouts.save(
            Payout.create(
                payoutId,
                batch.getId(),
                sellerId,
                recipient,
                settings.fundingAccountRef(),
                settings.payoutScenarioVersion(),
                netAmount,
                now));
    payoutJobs.save(PayoutJob.schedule(payout, now));
    return Optional.of(batch);
  }
}
