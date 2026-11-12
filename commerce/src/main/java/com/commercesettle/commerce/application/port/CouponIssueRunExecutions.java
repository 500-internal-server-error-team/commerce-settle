package com.commercesettle.commerce.application.port;

import java.util.Optional;
import java.util.UUID;

/** 월간 회차 Job의 실행자 잠금과 실행 이력 조회. 교정은 실행자가 없고 진행 중 실행이 남지 않은 회차만 다룬다. */
public interface CouponIssueRunExecutions {
  /** 회차의 실행자 잠금을 잡으면 풀 때 닫을 핸들을, 다른 실행자가 쥐고 있으면 빈 값을 돌려준다. */
  Optional<Held> tryLock(UUID runId);

  /** STARTING/STARTED/STOPPING처럼 진행 중으로 남은 실행이 있는지. 확인하지 못하면 예외다. */
  boolean hasActiveExecution(UUID runId);

  interface Held extends AutoCloseable {
    @Override
    void close();
  }
}
