package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountTest {
  @Test
  @DisplayName("이메일은 앞뒤 공백을 지우고 소문자로 맞춘다")
  void normalizesEmail() {
    assertThat(Account.normalizeEmail(" \tSeller.Kim@Example.COM "))
        .isEqualTo("seller.kim@example.com");
  }

  @Test
  @DisplayName("새 계정은 인증 버전 0의 활성 계정이다")
  void startsActiveWithInitialAuthVersion() {
    var account = SellerAccount.register("Seller@Example.com", "hash", "판매자", "ref", Instant.EPOCH);

    assertThat(account.getEmailNormalized()).isEqualTo("seller@example.com");
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.getAuthVersion()).isZero();
    assertThat(account.canLogIn()).isTrue();
  }
}
