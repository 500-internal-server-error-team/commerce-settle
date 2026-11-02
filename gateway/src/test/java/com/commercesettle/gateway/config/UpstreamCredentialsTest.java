package com.commercesettle.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpstreamCredentialsTest {
  private static final String VALID = "a".repeat(43);

  @Test
  @DisplayName("대상 서비스마다 자기 자격을 Bearer 값으로 돌려준다")
  void returnsCredentialOfTargetService() {
    final var credentials =
        new UpstreamCredentials(Map.of("commerce", VALID, "payment", "b".repeat(43)));

    assertThat(credentials.authorization("commerce")).isEqualTo("Bearer " + VALID);
    assertThat(credentials.authorization("payment")).isEqualTo("Bearer " + "b".repeat(43));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "short-credential", "contains space 0123456789abcdef0123456789abc"})
  @DisplayName("256비트 미만이거나 URL-safe가 아닌 자격이면 기동하지 않는다")
  void rejectsWeakCredential(String credential) {
    assertThatThrownBy(() -> new UpstreamCredentials(Map.of("commerce", credential)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(credential.isEmpty() ? "\u0000" : credential);
  }

  @Test
  @DisplayName("설정에 없는 대상에는 자격 없이 전달하지 않는다")
  void rejectsUnknownService() {
    final var credentials = new UpstreamCredentials(Map.of("commerce", VALID));

    assertThatThrownBy(() -> credentials.authorization("settlement"))
        .isInstanceOf(IllegalStateException.class);
  }
}
