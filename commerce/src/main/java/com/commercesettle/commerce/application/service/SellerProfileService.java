package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.AccountStatus;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.SellerAccount;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 본인 프로필과 Settlement용 지급 컨텍스트. 프로필 변경은 멱등 기록과 함께 한 트랜잭션에 저장하고, 판매 상태를 바꾸지 않는다. 지급 컨텍스트는 상품의 소유
 * 판매자와 그 판매자의 상태·수취 계정·버전을 같은 조회 시점에서 돌려준다.
 */
@Service
public class SellerProfileService {
  private static final String SELLER = "SELLER";

  private final AccountRepository accounts;
  private final ProductRepository products;
  private final IdempotentRequests idempotency;
  private final Clock clock;

  public SellerProfileService(
      AccountRepository accounts,
      ProductRepository products,
      IdempotentRequests idempotency,
      Clock clock) {
    this.accounts = accounts;
    this.products = products;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** Gateway가 검증한 판매자의 프로필. 삭제·정지된 판매자는 인증 실패로 본다. */
  @Transactional(readOnly = true)
  public SellerAccount profile(UUID sellerId) {
    return accounts
        .findById(ActorKind.SELLER, sellerId)
        .filter(Account::canLogIn)
        .map(SellerAccount.class::cast)
        .orElseThrow(AuthenticationFailedException::new);
  }

  /** 표시명·시험 수취 계정을 바꾼다. 같은 키·본문의 재요청은 현재 프로필을 돌려주고, 같은 키의 다른 본문은 충돌이다. 기대 버전이 다르면 멱등 기록도 롤백한다. */
  @Transactional
  public SellerAccount change(
      UUID sellerId,
      String idempotencyKey,
      String displayName,
      String payoutAccountRef,
      long expectedVersion) {
    return idempotency.execute(
        new IdempotentRequests.Request(
            SELLER,
            sellerId.toString(),
            "PROFILE_CHANGE",
            idempotencyKey,
            List.of(displayName, payoutAccountRef, expectedVersion)),
        sellerId,
        200,
        recordId -> {
          SellerAccount seller =
              accounts
                  .findLockedById(ActorKind.SELLER, sellerId)
                  .filter(Account::canLogIn)
                  .map(SellerAccount.class::cast)
                  .orElseThrow(AuthenticationFailedException::new);
          seller.changeProfile(displayName, payoutAccountRef, expectedVersion, clock.instant());
          // 응답의 버전이 증가한 값이 되도록 즉시 반영한다.
          return accounts.save(seller);
        },
        existingId -> profile(existingId));
  }

  /**
   * 상품의 소유 판매자와 지급 판단에 필요한 판매자 정보. 지난 판매의 지급을 막지 않도록 논리삭제한 상품도 조회하며, 삭제·정지 판매자는 해당 상태를 그대로 돌려준다.
   *
   * @throws ProductNotFoundException 상품이 없으면
   */
  @Transactional(readOnly = true)
  public SettlementContext settlementContext(UUID productId) {
    Product product =
        products.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    SellerAccount seller =
        accounts
            .findSellerIncludingDeleted(product.getSellerId())
            .orElseThrow(() -> new IllegalStateException("product seller is missing"));
    return new SettlementContext(
        product.getId(),
        seller.getId(),
        seller.getPayoutAccountRef(),
        seller.getVersion(),
        seller.getStatus());
  }

  /** 상품 한 건의 지급 컨텍스트. */
  public record SettlementContext(
      UUID productId,
      UUID sellerId,
      String payoutAccountRef,
      long sellerVersion,
      AccountStatus sellerStatus) {}
}
