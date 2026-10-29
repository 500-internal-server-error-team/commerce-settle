package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthChangeUnconfirmedException;
import com.commercesettle.commerce.application.port.AccountAuthGuard;
import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 계정 전체 세션 폐기. Redis 가드 선차단 → DB 변경 커밋 → 같은 변경의 가드 확정 반영 순서다. Redis와 DB를 한 트랜잭션으로 보지 않는다. 순서와 실패
 * 처리는 docs/policies.md#폐기와-저장소-복구를 따른다.
 */
@Component
public class AccountAuthChanges {
  private final AccountAuthGuard guards;

  public AccountAuthChanges(AccountAuthGuard guards) {
    this.guards = guards;
  }

  /**
   * change는 변경 ID를 받아 DB 트랜잭션을 커밋하고 확정 결과를 돌려준다. 차단에 실패하면 DB를 바꾸지 않고 그 예외를 그대로 던진다. 차단 뒤의 실패는 가드를
   * 막아 둔 채 AuthChangeUnconfirmedException이다. 막힌 가드는 이전 버전 세션과 새 로그인을 모두 거절한다.
   */
  public Confirmed apply(
      ActorKind kind, UUID actorId, long authVersion, Function<UUID, Confirmed> change) {
    UUID changeId = UUID.randomUUID();
    guards.block(kind, actorId, changeId, authVersion);
    try {
      Confirmed confirmed = change.apply(changeId);
      guards.confirm(kind, actorId, changeId, confirmed.authVersion(), confirmed.active());
      return confirmed;
    } catch (RuntimeException e) {
      throw new AuthChangeUnconfirmedException(changeId, e);
    }
  }

  /** DB에 커밋한 인증 버전과 로그인 가능 여부. */
  public record Confirmed(long authVersion, boolean active) {}
}
