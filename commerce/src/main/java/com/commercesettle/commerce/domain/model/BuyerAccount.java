package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "buyer_accounts")
public class BuyerAccount extends Account {
  protected BuyerAccount() {}

  private BuyerAccount(String email, String passwordHash, String displayName, Instant now) {
    super(email, passwordHash, displayName, now);
  }

  public static BuyerAccount register(
      String email, String passwordHash, String displayName, Instant now) {
    return new BuyerAccount(email, passwordHash, displayName, now);
  }

  @Override
  public ActorKind actorKind() {
    return ActorKind.BUYER;
  }
}
