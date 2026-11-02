package com.commercesettle.commerce.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternalCredentialsTest {
  private static final String GATEWAY = "g".repeat(43);
  private static final String PAYMENT = "p".repeat(43);

  @Test
  @DisplayName("자격과 일치하는 발신자 ID를 찾는다")
  void identifiesSenderByCredential() {
    final var credentials =
        new InternalCredentials(
            Map.of("gateway", List.of(GATEWAY), "service:payment", List.of(PAYMENT)), "gateway");

    assertThat(credentials.sender(GATEWAY)).contains("gateway");
    assertThat(credentials.sender(PAYMENT)).contains("service:payment");
    assertThat(credentials.sender("x".repeat(43))).isEmpty();
  }

  @Test
  @DisplayName("필수 발신자의 자격이 없으면 기동하지 않는다")
  void requiresGatewayCredential() {
    assertThatThrownBy(
            () -> new InternalCredentials(Map.of("service:payment", List.of(PAYMENT)), "gateway"))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"short-credential", "space included 0123456789abcdef0123456789abc"})
  @DisplayName("256비트 미만이거나 URL-safe가 아닌 자격이면 기동하지 않고 원문을 남기지 않는다")
  void rejectsWeakCredential(String credential) {
    assertThatThrownBy(
            () -> new InternalCredentials(Map.of("gateway", List.of(credential)), "gateway"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(credential);
  }

  @Test
  @DisplayName("정해진 형식이 아닌 발신자 ID는 기동하지 않는다")
  void rejectsUnsupportedSenderId() {
    assertThatThrownBy(
            () ->
                new InternalCredentials(
                    Map.of("gateway", List.of(GATEWAY), "settlement", List.of(PAYMENT)), "gateway"))
        .isInstanceOf(IllegalStateException.class);
  }
}
