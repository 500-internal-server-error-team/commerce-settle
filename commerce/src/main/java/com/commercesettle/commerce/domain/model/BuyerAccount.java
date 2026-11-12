package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "buyer_accounts")
public class BuyerAccount extends Account {
  protected BuyerAccount() {}

  private BuyerAccount(
      UUID id, String email, String passwordHash, String displayName, Instant now) {
    super(id, email, passwordHash, displayName, now);
  }

  public static BuyerAccount register(
      String email, String passwordHash, String displayName, Instant now) {
    return register(UUID.randomUUID(), email, passwordHash, displayName, now);
  }

  /** 가입 멱등 기록에 먼저 고정한 계정 ID로 만든다. */
  public static BuyerAccount register(
      UUID id, String email, String passwordHash, String displayName, Instant now) {
    return new BuyerAccount(id, email, passwordHash, displayName, now);
  }

  @Override
  public ActorKind actorKind() {
    return ActorKind.BUYER;
  }
}
