package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.dto.PayoutView;
import com.commercesettle.settlement.domain.exception.PayoutNotFoundException;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutJobStatus;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 본인의 정산 항목·배치·지급을 조회한다. 다른 판매자의 지급과 없는 지급은 구분하지 않는다. */
@Service
public class SellerSettlementQueryService {
  private final SettlementItemRepository items;
  private final SettlementBatchRepository batches;
  private final PayoutRepository payouts;
  private final PayoutJobRepository payoutJobs;

  public SellerSettlementQueryService(
      SettlementItemRepository items,
      SettlementBatchRepository batches,
      PayoutRepository payouts,
      PayoutJobRepository payoutJobs) {
    this.items = items;
    this.batches = batches;
    this.payouts = payouts;
    this.payoutJobs = payoutJobs;
  }

  @Transactional(readOnly = true)
  public PageView<SettlementItem> items(UUID sellerId, int page, int size) {
    return page(
        page, size, items.countBySeller(sellerId), () -> items.findBySeller(sellerId, page, size));
  }

  @Transactional(readOnly = true)
  public PageView<SettlementBatch> batches(UUID sellerId, int page, int size) {
    return page(
        page,
        size,
        batches.countBySeller(sellerId),
        () -> batches.findBySeller(sellerId, page, size));
  }

  @Transactional(readOnly = true)
  public PageView<PayoutView> payouts(UUID sellerId, int page, int size) {
    PageView<Payout> found =
        page(
            page,
            size,
            payouts.countBySeller(sellerId),
            () -> payouts.findBySeller(sellerId, page, size));
    Map<UUID, PayoutJob> jobs =
        found.items().isEmpty()
            ? Map.of()
            : payoutJobs
                .findByPayoutIds(found.items().stream().map(Payout::getId).toList())
                .stream()
                .collect(Collectors.toMap(PayoutJob::getPayoutId, Function.identity()));
    return new PageView<>(
        found.items().stream()
            .map(payout -> view(payout, Optional.ofNullable(jobs.get(payout.getId()))))
            .toList(),
        page,
        size,
        found.totalElements());
  }

  @Transactional(readOnly = true)
  public PayoutView payout(UUID sellerId, UUID payoutId) {
    Payout payout =
        payouts.findByIdAndSellerId(payoutId, sellerId).orElseThrow(PayoutNotFoundException::new);
    return view(payout, payoutJobs.findByPayoutId(payoutId));
  }

  private static <T> PageView<T> page(int page, int size, long total, Supplier<List<T>> rows) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid page");
    }
    // 시작 위치가 int를 넘는 페이지는 행이 있을 수 없으므로 조회하지 않는다.
    List<T> found = (long) page * size > Integer.MAX_VALUE ? List.of() : rows.get();
    return new PageView<>(found, page, size, total);
  }

  /** 다음 자동 시도는 작업이 대기 중일 때만 있다. 완료·확정 거절·운영 확인 대기는 자동 시도가 없고, 실행 중이면 이미 시도하고 있다. */
  private static PayoutView view(Payout payout, Optional<PayoutJob> job) {
    Instant next =
        job.filter(
                found ->
                    found.getStatus() == PayoutJobStatus.PENDING
                        || found.getStatus() == PayoutJobStatus.RETRY)
            .map(PayoutJob::getNextRunAt)
            .orElse(null);
    return new PayoutView(payout, next);
  }
}
