package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotentRequestsTest {
  @Test
  @DisplayName("요청 문자열에는 멱등키 원문과 본문 값을 넣지 않는다")
  void redactsKeyAndBodyInToString() {
    IdempotentRequests.Request request =
        new IdempotentRequests.Request(
            "SELLER", "seller-1", "PRODUCT_REGISTER", "secret-key-123", List.of("키보드", 39_000L));

    String text = request.toString();

    assertThat(text).contains("SELLER", "seller-1", "PRODUCT_REGISTER", "<redacted>");
    assertThat(text).doesNotContain("secret-key-123", "키보드", "39000");
  }
}
