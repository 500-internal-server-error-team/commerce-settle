package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.AccountStatus;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerAccount;
import com.commercesettle.commerce.domain.model.SellerAccount;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 커밋 경계와 DB 유일성을 확인하기 위해 테스트 트랜잭션 자동 롤백을 끄고 테스트마다 데이터를 지운다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAccountRepository.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountRepositoryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00.123456Z");

  @Autowired private AccountRepository accounts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  @AfterEach
  void deleteAccounts() {
    jdbc.update("DELETE FROM commerce.buyer_accounts");
    jdbc.update("DELETE FROM commerce.seller_accounts");
  }

  @Test
  @DisplayName("저장한 판매자를 정규화한 이메일과 ID로 다시 읽는다")
  void findsSavedSellerByEmailAndId() {
    SellerAccount saved = accounts.save(seller(" Seller@Example.com "));

    Account byEmail = accounts.findByEmail(ActorKind.SELLER, "seller@example.com").orElseThrow();
    Account byId = accounts.findById(ActorKind.SELLER, saved.getId()).orElseThrow();

    assertThat(byEmail.getId()).isEqualTo(saved.getId()).isEqualTo(byId.getId());
    assertThat(byEmail.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(byEmail.getAuthVersion()).isZero();
    assertThat(byEmail.getVersion()).isZero();
    assertThat(byEmail.getCreatedAt()).isEqualTo(NOW);
    assertThat(accounts.findByEmail(ActorKind.BUYER, "seller@example.com")).isEmpty();
  }

  @Test
  @DisplayName("같은 종류에 같은 이메일을 다시 저장하면 이메일 중복이다")
  void rejectsDuplicateEmailWithinActorKind() {
    accounts.save(seller("seller@example.com"));

    assertThatThrownBy(() -> accounts.save(seller("SELLER@example.com")))
        .isInstanceOf(DuplicateAccountEmailException.class);
  }

  @Test
  @DisplayName("구매자와 판매자는 같은 이메일로 각각 가입할 수 있다")
  void allowsSameEmailAcrossActorKinds() {
    accounts.save(seller("same@example.com"));

    accounts.save(BuyerAccount.register("same@example.com", "hash", "구매자", NOW));

    assertThat(accounts.findByEmail(ActorKind.BUYER, "same@example.com")).isPresent();
    assertThat(accounts.findByEmail(ActorKind.SELLER, "same@example.com")).isPresent();
  }

  @Test
  @DisplayName("삭제한 계정은 조회하지 않지만 이메일은 계속 중복으로 본다")
  void hidesDeletedAccountButKeepsEmail() {
    SellerAccount saved = accounts.save(seller("seller@example.com"));
    jdbc.update(
        "UPDATE commerce.seller_accounts SET status = 'DELETED', deleted_at = now() WHERE id = ?",
        saved.getId());

    assertThat(accounts.findByEmail(ActorKind.SELLER, "seller@example.com")).isEmpty();
    assertThat(accounts.findById(ActorKind.SELLER, saved.getId())).isEmpty();
    assertThat(accounts.existsByEmail(ActorKind.SELLER, "seller@example.com")).isTrue();
  }

  @Test
  @DisplayName("구매자 행 잠금은 삭제한 구매자를 찾지 않는다")
  void locksOnlyActiveBuyerRow() {
    BuyerAccount buyer =
        accounts.save(BuyerAccount.register("buyer@example.com", "hash", "구매자", NOW));
    TransactionTemplate tx = new TransactionTemplate(transactions);

    Boolean activeLocked =
        tx.execute(status -> accounts.findBuyerForUpdate(buyer.getId()).isPresent());
    jdbc.update(
        "UPDATE commerce.buyer_accounts SET status = 'DELETED', deleted_at = now() WHERE id = ?",
        buyer.getId());
    Boolean deletedLocked =
        tx.execute(status -> accounts.findBuyerForUpdate(buyer.getId()).isPresent());

    assertThat(activeLocked).isTrue();
    assertThat(deletedLocked).isFalse();
  }

  @Test
  @DisplayName("정지한 계정은 조회되지만 로그인할 수 없다")
  void loadsSuspendedAccountThatCannotLogIn() {
    SellerAccount saved = accounts.save(seller("seller@example.com"));
    jdbc.update(
        "UPDATE commerce.seller_accounts SET status = 'SUSPENDED' WHERE id = ?", saved.getId());

    Account found = accounts.findByEmail(ActorKind.SELLER, "seller@example.com").orElseThrow();

    assertThat(found.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    assertThat(found.canLogIn()).isFalse();
  }

  private static SellerAccount seller(String email) {
    return SellerAccount.register(email, "hash", "판매자", "payout-ref", NOW);
  }
}
