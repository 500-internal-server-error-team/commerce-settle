package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.SellerAccount;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
  private final AccountRepository accounts;
  private final PasswordHasher passwords;
  private final Clock clock;

  public AccountService(AccountRepository accounts, PasswordHasher passwords, Clock clock) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.clock = clock;
  }

  /** 이메일을 먼저 확인해 중복이면 비밀번호 해시를 계산하지 않는다. 동시 가입은 DB 유일성으로 하나만 남긴다. */
  public SellerAccount registerSeller(
      String email, String password, String displayName, String payoutAccountRef) {
    String emailNormalized = Account.normalizeEmail(email);
    if (accounts.existsByEmail(ActorKind.SELLER, emailNormalized)) {
      throw new DuplicateAccountEmailException();
    }
    return accounts.save(
        SellerAccount.register(
            emailNormalized,
            passwords.hash(password),
            displayName,
            payoutAccountRef,
            clock.instant()));
  }

  /** Gateway가 검증한 신원의 계정. 삭제·정지된 계정은 인증 실패로 본다. */
  public Account current(ActorKind kind, UUID id) {
    return accounts
        .findById(kind, id)
        .filter(Account::canLogIn)
        .orElseThrow(AuthenticationFailedException::new);
  }
}
