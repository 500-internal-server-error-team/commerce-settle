package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.DailySettlementSettings;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 한 명의 일배치. 대상 선점·지급 예약·합계·배치·지급·지급 작업을 한 트랜잭션에 고정한다. 지급 전 검증에 실패하면 선점까지 모두 롤백해 항목이 PAYABLE로 남고
 * 다음 실행에서 다시 고려된다.
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

  public SellerSettlementService(
      SettlementItemRepository items,
      SettlementBatchRepository batches,
      PayoutRepository payouts,
      PayoutJobRepository payoutJobs,
      ProductSettlementContextReader contexts,
      DailySettlementSettings settings,
      Clock clock) {
    this.items = items;
    this.batches = batches;
    this.payouts = payouts;
    this.payoutJobs = payoutJobs;
    this.contexts = contexts;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * 같은 판매자·날짜·정책 버전의 배치가 있으면 대상을 다시 고르지 않고 그 배치를 돌려준다. 대상이 없으면 배치를 만들지 않는다.
   *
   * @throws PayoutNotAllowedException 지급 전 검증에 실패하면. 호출자의 트랜잭션도 롤백해야 한다
   */
  @Transactional
  public Optional<SettlementBatch> settle(UUID sellerId, LocalDate batchDate) {
    return settleSelected(sellerId, batchDate, null);
  }

  /** 개발 시나리오의 지정 항목만 허용한다. 동시 유입된 일반 항목을 선점했으면 해당 판매자를 롤백한다. */
  @Transactional
  public Optional<SettlementBatch> settleFixture(
      UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    return settleSelected(sellerId, batchDate, java.util.Objects.requireNonNull(itemIds));
  }

  private Optional<SettlementBatch> settleSelected(
      UUID sellerId, LocalDate batchDate, Set<UUID> itemIds) {
    Optional<SettlementBatch> existing =
        batches.find(sellerId, batchDate, settings.policyVersion());
    if (existing.isPresent()) {
      if (itemIds != null
          && itemIds.stream()
              .map(items::findById)
              .flatMap(Optional::stream)
              .anyMatch(
                  item ->
                      item.getSellerId().equals(sellerId)
                          && item.getStatus() == SettlementItemStatus.PAYABLE
                          && item.getPayoutId() == null
                          && item.getReadyAt().isBefore(SettlementBatch.cutoffOf(batchDate)))) {
        throw new IllegalStateException(
            "Existing settlement batch conflicts with unprocessed fixture items");
      }
      return existing;
    }
    Instant now = clock.instant();
    Instant cutoffAt = SettlementBatch.cutoffOf(batchDate);
    Set<UUID> productIds = items.findPayableProductIds(sellerId, cutoffAt);
    if (productIds.isEmpty()) {
      return Optional.empty();
    }
    PayoutRecipient recipient =
        PayoutRecipient.resolve(sellerId, productIds, contexts.read(sellerId, productIds));

    UUID payoutId = UUID.randomUUID();
    List<ReservedItem> reserved = items.reserveForPayout(sellerId, cutoffAt, payoutId, now);
    if (reserved.isEmpty()) {
      return Optional.empty();
    }
    if (itemIds != null && reserved.stream().anyMatch(item -> !itemIds.contains(item.id()))) {
      throw new IllegalStateException("Fixture batch would reserve a non-fixture item");
    }
    // 검증과 선점 사이에 새 상품의 항목이 준비됐으면 검증하지 않은 상품이라 이번 실행에서 지급하지 않는다.
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
