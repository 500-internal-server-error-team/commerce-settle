package com.commercesettle.commerce.application.port;

/**
 * Redis 인증 저장소를 지금 믿고 쓸 수 있는지 판정한다. 세션 발급·폐기, 비밀번호 재설정, 인증 관련 계정 변경은 Redis를 쓰기 전에 확인한다. 기준은
 * docs/policies.md#폐기와-저장소-복구다.
 */
public interface AuthGate {
  /** 닫혀 있으면 AuthenticationUnavailableException(503)이다. */
  void requireOpen();
}
