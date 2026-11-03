package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.domain.model.BuyerCoupon;
import com.commercesettle.commerce.domain.model.CouponGrade;
import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import com.commercesettle.commerce.domain.model.CouponIssueTarget;
import com.commercesettle.commerce.domain.model.CouponIssueTargetStatus;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.repository.BuyerCouponRepository;
import com.commercesettle.commerce.domain.repository.CouponHistoryRepository;
import com.commercesettle.commerce.domain.repository.CouponIssueRunRepository;
import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository;
import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository.RankedBuyer;
import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository.Summary;
import com.commercesettle.commerce.domain.repository.CouponTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월간 우수고객 회차의 대상 확정·검증과 발급. 대상 저장·발급·종결은 회차 행을 먼저 잠그고 상태를 다시 확인한다. 확정 뒤에는 대상을 다시 선정하지 않고, 발급은 고정한
 * PENDING 대상만 한 명씩 쿠폰·이력·대상 ISSUED 전이를 한 트랜잭션으로 저장한다. 원래 기한이 지나면 새 쿠폰 없이 EXPIRED로 종결한다.
 */
@Service
public class GradeCouponService {
  private final CouponIssueRunRepository runs;
  private final CouponIssueTargetRepository targets;
  private final CouponTemplateRepository templates;
  private final BuyerCouponRepository coupons;
  private final CouponHistoryRepository histories;
  private final Clock clock;

  public GradeCouponService(
      CouponIssueRunRepository runs,
      CouponIssueTargetRepository targets,
      CouponTemplateRepository templates,
      BuyerCouponRepository coupons,
      CouponHistoryRepository histories,
      Clock clock) {
    this.runs = runs;
    this.targets = targets;
    this.templates = templates;
    this.coupons = coupons;
    this.histories = histories;
    this.clock = clock;
  }

  /** 혜택 월보다 이전인데 끝나지 않은 회차의 혜택 월. 스케줄러가 기한 경과 종결까지 이어서 실행한다. */
  @Transactional(readOnly = true)
  public List<LocalDate> unfinishedMonthsBefore(LocalDate targetMonth) {
    return runs.findUnfinishedMonthsBefore(CouponIssueRun.firstDay(targetMonth));
  }

  /** 혜택 월의 취소되지 않은 회차. 월이 마감됐는데 없으면 최초 회차를 만든다. 마감 전이면 비어 있다. */
  @Transactional
  public Optional<CouponIssueRun> currentRun(LocalDate targetMonth) {
    LocalDate month = CouponIssueRun.firstDay(targetMonth);
    Instant now = clock.instant();
    if (now.isBefore(CouponIssueRun.monthStart(month))) {
      return Optional.empty();
    }
    Optional<CouponIssueRun> existing = runs.findCurrent(month);
    if (existing.isPresent()) {
      return existing;
    }
    runs.saveIfAbsent(CouponIssueRun.open(UUID.randomUUID(), month, now));
    return runs.findCurrent(month);
  }

  /**
   * 첫 단계: 마감 원자료로 대상·순위·등급을 고정하고 검증한 뒤 대상 수·경계 금액과 ISSUING을 확정한다. 검증에 실패하면 예외로 롤백해 PREPARING을 보존하고
   * 발급하지 않는다. 이미 확정한 회차는 저장한 결과를 그대로 쓴다.
   */
  @Transactional
  public CouponIssueRunStatus prepare(UUID runId) {
    CouponIssueRun run = lockRun(runId);
    Instant now = clock.instant();
    if (run.getStatus() != CouponIssueRunStatus.PREPARING) {
      return run.getStatus();
    }
    if (run.isExpiredAt(now)) {
      run.expire(now);
      return runs.save(run).getStatus();
    }
    List<RankedBuyer> ranked = targets.rankSpenders(run.spendFrom(), run.getCutoffAt());
    Map<CouponGrade, UUID> gradeTemplates = gradeTemplates(run.getTargetMonth(), now);
    targets.saveAll(runId, ranked, gradeTemplates, now);
    Long cutoffSpend = verify(run, ranked);
    run.confirm(ranked.size(), cutoffSpend, now);
    return runs.save(run).getStatus();
  }

  /** 발급할 PENDING 대상. 순위·구매자 순서다. */
  @Transactional(readOnly = true)
  public List<UUID> pendingTargetIds(UUID runId, int limit) {
    return targets.findPendingIds(runId, limit);
  }

  /**
   * 대상 한 명에게 발급한다. 같은 구매자의 혜택 월 쿠폰이 이미 있으면 원쿠폰을 재사용한다. 대상이 이미 처리됐으면 아무것도 하지 않는다. 회차가 ISSUING이 아니거나
   * 원래 기한이 지났으면 발급하지 않고 false를 돌려주며, 남은 대상은 종결 처리가 회차와 함께 EXPIRED로 둔다.
   */
  @Transactional
  public boolean issue(UUID runId, UUID targetId) {
    CouponIssueRun run = lockRun(runId);
    CouponIssueTarget target =
        targets
            .findByIdForUpdate(targetId)
            .filter(value -> value.getRunId().equals(runId))
            .orElseThrow(() -> missing("target", targetId));
    Instant now = clock.instant();
    if (run.getStatus() != CouponIssueRunStatus.ISSUING || run.isExpiredAt(now)) {
      return false;
    }
    if (target.getStatus() != CouponIssueTargetStatus.PENDING) {
      return true;
    }
    Optional<BuyerCoupon> original = coupons.findGrade(target.getBuyerId(), run.getTargetMonth());
    if (original.isPresent()) {
      target.issue(original.get().getId(), now);
      targets.save(target);
      return true;
    }
    UUID templateId = target.getTemplateId();
    CouponTemplate template =
        templates.findById(templateId).orElseThrow(() -> missing("template", templateId));
    BuyerCoupon.Issued issued =
        BuyerCoupon.issueGrade(UUID.randomUUID(), template, target.getBuyerId(), targetId, now);
    coupons.save(issued.coupon());
    histories.save(issued.history());
    templates.addTotals(template.getId(), 1, issued.history().getCommittedDelta(), 0);
    target.issue(issued.coupon().getId(), now);
    targets.save(target);
    return true;
  }

  /** 남은 대상이 없으면 완료하고, 원래 기한이 지났으면 남은 대상과 회차를 EXPIRED로 종결한다. */
  @Transactional
  public CouponIssueRunStatus finish(UUID runId) {
    CouponIssueRun run = lockRun(runId);
    if (run.getStatus() != CouponIssueRunStatus.ISSUING) {
      return run.getStatus();
    }
    Instant now = clock.instant();
    List<UUID> pending = targets.findPendingIds(runId, Integer.MAX_VALUE);
    if (!pending.isEmpty() && !run.isExpiredAt(now)) {
      return run.getStatus();
    }
    for (UUID targetId : pending) {
      CouponIssueTarget target =
          targets.findByIdForUpdate(targetId).orElseThrow(() -> missing("target", targetId));
      target.expire(now);
      targets.save(target);
    }
    if (pending.isEmpty()) {
      run.complete(now);
    } else {
      run.expire(now);
    }
    return runs.save(run).getStatus();
  }

  private Map<CouponGrade, UUID> gradeTemplates(LocalDate month, Instant now) {
    Map<CouponGrade, UUID> ids = new EnumMap<>(CouponGrade.class);
    for (CouponGrade grade : CouponGrade.values()) {
      templates.saveGradeIfAbsent(CouponTemplate.grade(UUID.randomUUID(), grade, month, now));
      ids.put(
          grade,
          templates
              .findGrade(grade.name(), month)
              .orElseThrow(() -> new IllegalStateException("grade template is missing: " + grade))
              .getId());
    }
    return ids;
  }

  /**
   * 저장한 대상이 원자료 순위와 같고, 100위 경계의 동점자가 모두 들어갔고, 구매자가 중복되지 않고, 등급별 할인 합계가 맞는지 확인한다. 대상이 있으면 경계 금액을
   * 돌려준다.
   */
  private Long verify(CouponIssueRun run, List<RankedBuyer> ranked) {
    Summary summary = targets.summarize(run.getId());
    long expectedDiscount =
        ranked.stream().mapToLong(b -> CouponGrade.of(b.externalSpend()).discountAmount()).sum();
    if (summary.count() != ranked.size() || summary.discountTotal() != expectedDiscount) {
      throw new IllegalStateException("stored targets do not match ranking of run " + run.getId());
    }
    if (new HashSet<>(ranked.stream().map(RankedBuyer::buyerId).toList()).size() != ranked.size()
        || ranked.stream().anyMatch(b -> b.rankNo() < 1 || b.rankNo() > 100)) {
      throw new IllegalStateException("ranking has duplicate buyers or invalid ranks");
    }
    if (ranked.isEmpty()) {
      return null;
    }
    long cutoffSpend = ranked.get(ranked.size() - 1).externalSpend();
    long atLeast = targets.countSpendersAtLeast(run.spendFrom(), run.getCutoffAt(), cutoffSpend);
    if (atLeast != ranked.size()) {
      throw new IllegalStateException(
          "tied buyers at the boundary are missing in run " + run.getId());
    }
    return cutoffSpend;
  }

  private CouponIssueRun lockRun(UUID runId) {
    return runs.findByIdForUpdate(runId).orElseThrow(() -> missing("run", runId));
  }

  private static IllegalStateException missing(String what, UUID id) {
    return new IllegalStateException("coupon issue " + what + " is missing: " + id);
  }
}
