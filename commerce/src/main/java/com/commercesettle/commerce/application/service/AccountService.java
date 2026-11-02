package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.application.port.RegistrationRequestHasher;
import com.commercesettle.commerce.domain.exception.AccountNotFoundException;
import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerAccount;
import com.commercesettle.commerce.domain.model.SellerAccount;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final AccountRepository accounts;
  private final PasswordHasher passwords;
  private final RegistrationRequestHasher registrationHashes;
  private final IdempotentRequests idempotent;
  private final CouponService coupons;
  private final Clock clock;

  public AccountService(
      AccountRepository accounts,
      PasswordHasher passwords,
      RegistrationRequestHasher registrationHashes,
      IdempotentRequests idempotent,
      CouponService coupons,
      Clock clock) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.registrationHashes = registrationHashes;
    this.idempotent = idempotent;
    this.coupons = coupons;
    this.clock = clock;
  }

  /**
   * 구매자 계정·웰컴 쿠폰·멱등 완료 기록을 한 트랜잭션에 저장한다. 계정 ID가 아직 없으므로 서버가 고정한 공개 가입 범위로 키를 찾는다. 같은 키·본문은 기존 계정을
   * 돌려주고 쿠폰을 더 발급하지 않는다. 기록이 없거나 만료된 뒤의 같은 이메일은 이메일 유일성으로 409다.
   */
  @Transactional
  public BuyerAccount registerBuyer(
      String idempotencyKey, String email, String password, String displayName) {
    String emailNormalized = Account.normalizeEmail(email);
    // 멱등 기록에는 서버 키 HMAC에서 다시 만든 해시만 남는다. 비밀번호 원문으로는 대조할 수 없다.
    var request =
        new IdempotentRequests.Request(
            "PUBLIC",
            "BUYER_REGISTRATION",
            "REGISTER",
            idempotencyKey,
            List.of(registrationHashes.hash(emailNormalized, password, displayName)));
    UUID accountId = UUID.randomUUID();
    return idempotent.execute(
        request,
        accountId,
        201,
        recordId -> createBuyer(accountId, emailNormalized, password, displayName),
        this::registeredBuyer);
  }

  private BuyerAccount createBuyer(
      UUID accountId, String emailNormalized, String password, String displayName) {
    if (accounts.existsByEmail(ActorKind.BUYER, emailNormalized)) {
      throw new DuplicateAccountEmailException();
    }
    BuyerAccount buyer =
        accounts.save(
            BuyerAccount.register(
                accountId,
                emailNormalized,
                passwords.hash(password),
                displayName,
                clock.instant()));
    coupons.issueWelcome(buyer.getId(), buyer.getCreatedAt());
    return buyer;
  }

  private BuyerAccount registeredBuyer(UUID accountId) {
    return accounts
        .findById(ActorKind.BUYER, accountId)
        .map(BuyerAccount.class::cast)
        .orElseThrow(AccountNotFoundException::new);
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
