package com.commercesettle.financemock.application.dto;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import java.util.UUID;

/** POST는 불변 기초잔액으로 원생성 결과를 복원하고 GET만 현재 잔액·예약·버전을 반환한다. */
public record MockAccountView(
    UUID id,
    String providerId,
    String accountRef,
    AccountPurpose purpose,
    long openingBalance,
    long balance,
    long reservedAmount,
    long version) {
  public static MockAccountView created(MockAccount account) {
    return new MockAccountView(
        account.getId(),
        account.getProviderId(),
        account.getAccountRef(),
        account.getPurpose(),
        account.getOpeningBalance(),
        account.getOpeningBalance(),
        0,
        0);
  }

  public static MockAccountView current(MockAccount account) {
    return new MockAccountView(
        account.getId(),
        account.getProviderId(),
        account.getAccountRef(),
        account.getPurpose(),
        account.getOpeningBalance(),
        account.getBalance(),
        account.getReservedAmount(),
        account.getVersion());
  }
}
