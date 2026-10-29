package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.AuthChangeUnconfirmedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.port.AccountAuthGuard;
import com.commercesettle.commerce.application.service.AccountAuthChanges.Confirmed;
import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountAuthChangesTest {
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private final List<String> calls = new ArrayList<>();
  private final List<UUID> changeIds = new ArrayList<>();
  private RuntimeException blockFailure;
  private RuntimeException confirmFailure;

  private final AccountAuthChanges changes =
      new AccountAuthChanges(
          new AccountAuthGuard() {
            @Override
            public void block(ActorKind kind, UUID actorId, UUID changeId, long authVersion) {
              calls.add("block:" + authVersion);
              changeIds.add(changeId);
              if (blockFailure != null) {
                throw blockFailure;
              }
            }

            @Override
            public void confirm(
                ActorKind kind, UUID actorId, UUID changeId, long authVersion, boolean active) {
              calls.add("confirm:" + authVersion + ":" + active);
              changeIds.add(changeId);
              if (confirmFailure != null) {
                throw confirmFailure;
              }
            }
          });

  @Test
  @DisplayName("가드 차단 → DB 변경 → 같은 변경 ID의 확정 반영 순서로 실행한다")
  void blocksThenChangesThenConfirmsSameChange() {
    Confirmed confirmed = changes.apply(ActorKind.SELLER, ACTOR_ID, 4, this::commit);

    assertThat(confirmed).isEqualTo(new Confirmed(5, true));
    assertThat(calls).containsExactly("block:4", "commit", "confirm:5:true");
    assertThat(changeIds).hasSize(3).containsOnly(changeIds.getFirst());
  }

  @Test
  @DisplayName("가드를 막지 못하면 DB를 바꾸지 않고 차단 실패를 그대로 던진다")
  void skipsChangeWhenBlockFails() {
    blockFailure = new AuthenticationUnavailableException("pending");

    assertThatThrownBy(() -> changes.apply(ActorKind.SELLER, ACTOR_ID, 4, this::commit))
        .isSameAs(blockFailure);
    assertThat(calls).containsExactly("block:4");
  }

  @Test
  @DisplayName("차단 뒤 DB 변경이 실패하면 확정 반영 없이 미확정으로 알린다")
  void leavesGuardBlockedWhenChangeFails() {
    assertThatThrownBy(
            () ->
                changes.apply(
                    ActorKind.SELLER,
                    ACTOR_ID,
                    4,
                    changeId -> {
                      throw new IllegalStateException("commit failed");
                    }))
        .isInstanceOfSatisfying(
            AuthChangeUnconfirmedException.class,
            e -> assertThat(e.changeId()).isEqualTo(changeIds.getFirst()));
    assertThat(calls).containsExactly("block:4");
  }

  @Test
  @DisplayName("커밋 뒤 확정 반영이 실패해도 미확정으로 알린다")
  void reportsUnconfirmedWhenConfirmFails() {
    confirmFailure = new AuthenticationUnavailableException("redis down");

    assertThatThrownBy(() -> changes.apply(ActorKind.SELLER, ACTOR_ID, 4, this::commit))
        .isInstanceOf(AuthChangeUnconfirmedException.class)
        .hasCause(confirmFailure);
    assertThat(calls).containsExactly("block:4", "commit", "confirm:5:true");
  }

  private Confirmed commit(UUID changeId) {
    calls.add("commit");
    changeIds.add(changeId);
    return new Confirmed(5, true);
  }
}
