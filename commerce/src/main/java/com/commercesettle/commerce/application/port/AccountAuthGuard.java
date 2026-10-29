package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.UUID;

/** Redis 계정별 인증 가드의 선차단과 확정 반영. 계약은 docs/data-model.md#계정별-인증-가드를 따른다. */
public interface AccountAuthGuard {
  /**
   * 가드가 authVersion의 ACTIVE이거나 없을 때 changeId의 변경 중 BLOCKED로 바꾼다. 확정 BLOCKED이거나 버전이 다르면
   * AuthenticationFailedException, 다른 변경이 진행 중이거나 손상된 가드와 저장소 오류는
   * AuthenticationUnavailableException이다. 실패하면 가드를 바꾸지 않는다.
   */
  void block(ActorKind kind, UUID actorId, UUID changeId, long authVersion);

  /**
   * changeId의 변경 중인 가드에만 DB에서 확정한 authVersion과 상태를 반영하고 변경 표시를 지운다. 다른 변경이거나 이전 버전이면 반영하지 않고
   * AuthenticationUnavailableException이다.
   */
  void confirm(ActorKind kind, UUID actorId, UUID changeId, long authVersion, boolean active);
}
