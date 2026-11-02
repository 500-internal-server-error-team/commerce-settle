package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CouponIssueRunRepository {
  CouponIssueRun save(CouponIssueRun run);

  /** 혜택 월의 취소되지 않은 회차가 없을 때만 저장한다. 동시에 만들면 하나만 남는다. */
  void saveIfAbsent(CouponIssueRun run);

  /** 혜택 월의 취소되지 않은 회차. */
  Optional<CouponIssueRun> findCurrent(LocalDate targetMonth);

  /** 대상 저장·발급·종결 전에 회차 행을 잠그고 상태를 다시 확인한다. */
  Optional<CouponIssueRun> findByIdForUpdate(UUID runId);
}
