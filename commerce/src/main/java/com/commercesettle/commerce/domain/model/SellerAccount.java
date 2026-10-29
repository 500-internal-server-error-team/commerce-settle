package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "seller_accounts")
public class SellerAccount extends Account {
  // 시험용 지급 계정 참조. 실계좌 정보가 아니다.
  private String payoutAccountRef;

  protected SellerAccount() {}

  private SellerAccount(
      String email, String passwordHash, String displayName, String payoutAccountRef, Instant now) {
    super(email, passwordHash, displayName, now);
    this.payoutAccountRef = Objects.requireNonNull(payoutAccountRef, "payoutAccountRef");
  }

  public static SellerAccount register(
      String email, String passwordHash, String displayName, String payoutAccountRef, Instant now) {
    return new SellerAccount(email, passwordHash, displayName, payoutAccountRef, now);
  }

  @Override
  public ActorKind actorKind() {
    return ActorKind.SELLER;
  }

  public String getPayoutAccountRef() {
    return payoutAccountRef;
  }
}
