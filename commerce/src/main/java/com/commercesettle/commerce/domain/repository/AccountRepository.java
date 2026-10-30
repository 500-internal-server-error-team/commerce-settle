package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerAccount;
import com.commercesettle.commerce.domain.model.SellerAccount;
import java.util.Optional;
import java.util.UUID;

/** 계정 종류별 저장소. 조회는 논리삭제한 계정을 제외하고, 이메일 중복 판정은 삭제한 계정도 포함한다. */
public interface AccountRepository {
  Optional<Account> findByEmail(ActorKind kind, String emailNormalized);

  Optional<Account> findById(ActorKind kind, UUID id);

  /** 인증 정보를 바꾸는 동안 같은 계정의 다른 변경을 기다리게 행을 잠근다. 트랜잭션 안에서만 호출한다. */
  Optional<Account> findLockedById(ActorKind kind, UUID id);

  boolean existsByEmail(ActorKind kind, String emailNormalized);

  /** 이메일 유일성 위반은 DuplicateAccountEmailException이다. */
  BuyerAccount save(BuyerAccount account);

  /** 이메일 유일성 위반은 DuplicateAccountEmailException이다. */
  SellerAccount save(SellerAccount account);
}
