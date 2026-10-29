package com.commercesettle.commerce.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AesGcmPasswordResetDeliveryTest {
  private static final String TOKEN = "reset-token-reset-token-reset-token-1234567";
  private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private final AesGcmPasswordResetDelivery delivery = delivery("d".repeat(32));

  @Test
  @DisplayName("같은 키와 토큰 ID로 연 암호문은 원래 토큰이고 원문을 그대로 담지 않는다")
  void sealsTokenForDelivery() {
    byte[] sealed = delivery.seal(TOKEN_ID, TOKEN);

    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN);
    assertThat(delivery.open(TOKEN_ID, sealed)).isEqualTo(TOKEN);
    assertThat(delivery.seal(TOKEN_ID, TOKEN)).isNotEqualTo(sealed);
  }

  @Test
  @DisplayName("다른 토큰 행이나 다른 키로는 열지 못한다")
  void refusesOtherTokenIdOrKey() {
    byte[] sealed = delivery.seal(TOKEN_ID, TOKEN);

    assertThatThrownBy(() -> delivery.open(UUID.randomUUID(), sealed))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> delivery("e".repeat(32)).open(TOKEN_ID, sealed))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AesGcmPasswordResetDelivery delivery(String key) {
    return new AesGcmPasswordResetDelivery(
        new AuthProperties(
            UUID.randomUUID(),
            new AuthProperties.RateLimit("k".repeat(32), 5, 900, 3, 3600),
            new AuthProperties.PasswordReset(key)));
  }
}
