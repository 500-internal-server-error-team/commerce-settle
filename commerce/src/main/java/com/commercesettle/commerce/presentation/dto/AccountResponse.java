package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Account")
public record AccountResponse(
    UUID id,
    @Schema(allowableValues = {"BUYER", "SELLER"}) String actorKind,
    String displayName,
    @Schema(allowableValues = {"ACTIVE", "SUSPENDED", "DELETED"}) String status,
    long version) {

  public static AccountResponse from(Account account) {
    return new AccountResponse(
        account.getId(),
        account.actorKind().name(),
        account.getDisplayName(),
        account.getStatus().name(),
        account.getVersion());
  }
}
