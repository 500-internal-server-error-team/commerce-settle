package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.ActorKind;

/** 작업·계정 종류·정규화한 이메일별 인증 요청 제한. 계정 존재 여부와 관계없이 형식이 유효한 모든 요청을 세며, 로그인과 복구 요청은 예산을 공유하지 않는다. */
public interface AuthRateLimiter {
  /**
   * 요청을 한 번 세고 상한 안이면 그대로 돌아온다. 상한을 넘으면 AuthRateLimitedException, 제한을 확정할 수 없으면
   * AuthenticationUnavailableException이다.
   */
  void acquire(Operation operation, ActorKind kind, String emailNormalized);

  enum Operation {
    LOGIN,
    PASSWORD_RESET_REQUEST
  }
}
