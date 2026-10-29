package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerAccount;
import com.commercesettle.commerce.domain.model.SellerAccount;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaAccountRepository implements AccountRepository {
  private final SpringDataBuyerAccountRepository buyers;
  private final SpringDataSellerAccountRepository sellers;

  JpaAccountRepository(
      SpringDataBuyerAccountRepository buyers, SpringDataSellerAccountRepository sellers) {
    this.buyers = buyers;
    this.sellers = sellers;
  }

  @Override
  public Optional<Account> findByEmail(ActorKind kind, String emailNormalized) {
    return switch (kind) {
      case BUYER ->
          buyers.findByEmailNormalizedAndDeletedAtIsNull(emailNormalized).map(Account.class::cast);
      case SELLER ->
          sellers.findByEmailNormalizedAndDeletedAtIsNull(emailNormalized).map(Account.class::cast);
    };
  }

  @Override
  public Optional<Account> findById(ActorKind kind, UUID id) {
    return switch (kind) {
      case BUYER -> buyers.findByIdAndDeletedAtIsNull(id).map(Account.class::cast);
      case SELLER -> sellers.findByIdAndDeletedAtIsNull(id).map(Account.class::cast);
    };
  }

  @Override
  public Optional<Account> findLockedById(ActorKind kind, UUID id) {
    return switch (kind) {
      case BUYER -> buyers.findLockedById(id).map(Account.class::cast);
      case SELLER -> sellers.findLockedById(id).map(Account.class::cast);
    };
  }

  @Override
  public boolean existsByEmail(ActorKind kind, String emailNormalized) {
    return switch (kind) {
      case BUYER -> buyers.existsByEmailNormalized(emailNormalized);
      case SELLER -> sellers.existsByEmailNormalized(emailNormalized);
    };
  }

  @Override
  public BuyerAccount save(BuyerAccount account) {
    return saveNew(() -> buyers.saveAndFlush(account), "uk_buyer_accounts_email");
  }

  @Override
  public SellerAccount save(SellerAccount account) {
    return saveNew(() -> sellers.saveAndFlush(account), "uk_seller_accounts_email");
  }

  // 즉시 flush해 이메일 유일성 위반을 커밋 시점이 아닌 저장 호출에서 업무 예외로 바꾼다.
  private static <T> T saveNew(Supplier<T> save, String emailConstraint) {
    try {
      return save.get();
    } catch (DataIntegrityViolationException e) {
      if (e.getCause() instanceof ConstraintViolationException violation
          && emailConstraint.equals(violation.getConstraintName())) {
        throw new DuplicateAccountEmailException();
      }
      throw e;
    }
  }
}
