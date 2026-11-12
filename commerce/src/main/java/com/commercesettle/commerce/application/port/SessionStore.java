package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.ActorKind;
import java.time.Instant;
import java.util.UUID;

/** Redis 로그인 세션. 계약은 docs/data-model.md#redis-인증-데이터를 따른다. */
public interface SessionStore {
  /**
   * 계정별 인증 가드가 authVersion의 ACTIVE일 때만 고정 만료 세션을 저장한다. 가드가 없으면 같은 버전의 ACTIVE로 만든다. 가드가 BLOCKED이거나
   * 버전이 다르면 AuthenticationFailedException, 변경 중·손상된 가드와 저장소 오류는
   * AuthenticationUnavailableException이다.
   */
  IssuedSession issue(ActorKind kind, UUID actorId, long authVersion);

  /**
   * 현재 세션을 삭제한다. 이미 없으면 삭제된 것으로 본다. 다른 계정의 세션이면 AuthenticationFailedException, 삭제를 확인하지 못하면
   * AuthenticationUnavailableException이다.
   */
  void delete(String tokenHash, ActorKind kind, UUID actorId);

  /** token은 응답에서 한 번만 반환하는 원문이며 저장하지 않는다. */
  record IssuedSession(String token, Instant expiresAt) {}
}
