package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.ActorKind;

/** 계정 종류·정규화한 이메일별 로그인 시도 제한. 계정 존재 여부와 관계없이 형식이 유효한 모든 시도를 센다. */
public interface LoginRateLimiter {
  /**
   * 시도를 한 번 세고 상한 안이면 그대로 돌아온다. 상한을 넘으면 LoginRateLimitedException, 제한을 확정할 수 없으면
   * AuthenticationUnavailableException이다.
   */
  void acquire(ActorKind kind, String emailNormalized);
}
