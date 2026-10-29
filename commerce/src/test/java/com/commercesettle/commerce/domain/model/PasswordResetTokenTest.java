package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordResetTokenTest {
  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

  @Test
  @DisplayName("발급한 계정 종류에서 만료 10분 전까지만 쓸 수 있다")
  void isUsableForSameKindUntilExpiry() {
    PasswordResetToken token = issue();

    assertThat(token.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    assertThat(token.isUsableFor(ActorKind.SELLER, NOW.plusSeconds(599))).isTrue();
    assertThat(token.isUsableFor(ActorKind.SELLER, NOW.plusSeconds(600))).isFalse();
    assertThat(token.isUsableFor(ActorKind.BUYER, NOW)).isFalse();
  }

  @Test
  @DisplayName("소비하거나 폐기한 토큰은 다시 쓰거나 닫을 수 없다")
  void closesOnlyOnce() {
    PasswordResetToken consumed = issue();
    consumed.consume(NOW);
    PasswordResetToken revoked = issue();
    revoked.revoke(NOW);

    assertThat(consumed.isUsableFor(ActorKind.SELLER, NOW)).isFalse();
    assertThat(revoked.isUsableFor(ActorKind.SELLER, NOW)).isFalse();
    assertThatThrownBy(() -> consumed.revoke(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> revoked.consume(NOW)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("토큰 해시는 UTF-8 SHA-256의 소문자 16진수다")
  void hashesTokenAsLowercaseSha256() {
    assertThat(PasswordResetToken.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  private static PasswordResetToken issue() {
    return PasswordResetToken.issue(
        UUID.randomUUID(),
        ActorKind.SELLER,
        UUID.randomUUID(),
        "a".repeat(64),
        new byte[] {1},
        NOW);
  }
}
