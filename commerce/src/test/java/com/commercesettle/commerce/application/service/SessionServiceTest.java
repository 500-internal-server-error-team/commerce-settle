package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.AuthRateLimitedException;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.AuthRateLimiter;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.application.port.SessionStore;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerAccount;
import com.commercesettle.commerce.domain.model.SellerAccount;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
  private static final String PASSWORD = "correct-password";

  private final List<String> checkedHashes = new ArrayList<>();
  private final List<UUID> issuedFor = new ArrayList<>();
  private final List<String> limited = new ArrayList<>();
  private Account stored;
  private boolean rateLimited;
  private SessionService service;

  @BeforeEach
  void setUp() {
    PasswordHasher hasher =
        new PasswordHasher() {
          @Override
          public String hash(String rawPassword) {
            return "hash:" + rawPassword;
          }

          @Override
          public boolean matches(String rawPassword, String passwordHash) {
            checkedHashes.add(passwordHash);
            return passwordHash.equals("hash:" + rawPassword);
          }
        };
    AuthRateLimiter limiter =
        (operation, kind, email) -> {
          limited.add(operation + ":" + kind + ":" + email);
          if (rateLimited) {
            throw new AuthRateLimitedException(30);
          }
        };
    SessionStore sessions =
        new SessionStore() {
          @Override
          public IssuedSession issue(ActorKind kind, UUID actorId, long authVersion) {
            issuedFor.add(actorId);
            return new IssuedSession("token", NOW.plusSeconds(1800));
          }

          @Override
          public void delete(String tokenHash, ActorKind kind, UUID actorId) {}
        };
    service = new SessionService(new SingleAccountRepository(), hasher, limiter, sessions);
    checkedHashes.clear();
  }

  @Test
  @DisplayName("이메일을 정규화해 제한을 센 뒤 비밀번호와 상태가 맞으면 세션을 발급한다")
  void issuesSessionForActiveAccount() {
    stored = SellerAccount.register("seller@example.com", "hash:" + PASSWORD, "판매자", "ref", NOW);

    SessionService.Login login = service.login(ActorKind.SELLER, " Seller@Example.COM ", PASSWORD);

    assertThat(login.token()).isEqualTo("token");
    assertThat(login.account()).isSameAs(stored);
    assertThat(limited).containsExactly("LOGIN:SELLER:seller@example.com");
    assertThat(issuedFor).containsExactly(stored.getId());
  }

  @Test
  @DisplayName("없는 계정도 비밀번호 해시 검사를 거친 뒤 같은 인증 실패로 거절한다")
  void checksPasswordHashEvenWithoutAccount() {
    assertThatThrownBy(() -> service.login(ActorKind.BUYER, "none@example.com", PASSWORD))
        .isInstanceOf(AuthenticationFailedException.class);

    assertThat(checkedHashes).hasSize(1);
    assertThat(issuedFor).isEmpty();
  }

  @Test
  @DisplayName("비밀번호가 틀리면 세션을 발급하지 않는다")
  void rejectsWrongPassword() {
    stored = BuyerAccount.register("buyer@example.com", "hash:" + PASSWORD, "구매자", NOW);

    assertThatThrownBy(() -> service.login(ActorKind.BUYER, "buyer@example.com", "wrong-password"))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(issuedFor).isEmpty();
  }

  @Test
  @DisplayName("제한에 걸리면 계정 조회·비밀번호 검사 없이 거절한다")
  void stopsBeforePasswordCheckWhenLimited() {
    stored = BuyerAccount.register("buyer@example.com", "hash:" + PASSWORD, "구매자", NOW);
    rateLimited = true;

    assertThatThrownBy(() -> service.login(ActorKind.BUYER, "buyer@example.com", PASSWORD))
        .isInstanceOf(AuthRateLimitedException.class);
    assertThat(checkedHashes).isEmpty();
    assertThat(issuedFor).isEmpty();
  }

  private class SingleAccountRepository implements AccountRepository {
    @Override
    public Optional<Account> findByEmail(ActorKind kind, String emailNormalized) {
      return Optional.ofNullable(stored)
          .filter(a -> a.actorKind() == kind && a.getEmailNormalized().equals(emailNormalized));
    }

    @Override
    public Optional<Account> findById(ActorKind kind, UUID id) {
      return Optional.ofNullable(stored).filter(a -> a.actorKind() == kind && a.getId().equals(id));
    }

    @Override
    public Optional<Account> findLockedById(ActorKind kind, UUID id) {
      return findById(kind, id);
    }

    @Override
    public boolean existsByEmail(ActorKind kind, String emailNormalized) {
      return findByEmail(kind, emailNormalized).isPresent();
    }

    @Override
    public BuyerAccount save(BuyerAccount account) {
      return account;
    }

    @Override
    public SellerAccount save(SellerAccount account) {
      return account;
    }
  }
}
