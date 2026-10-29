package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.LoginRateLimiter;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.application.port.SessionStore;
import com.commercesettle.commerce.application.port.SessionStore.IssuedSession;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 로그인 세션 발급과 현재 세션 로그아웃. */
@Service
public class SessionService {
  private final AccountRepository accounts;
  private final PasswordHasher passwords;
  private final LoginRateLimiter rateLimiter;
  private final SessionStore sessions;
  // 없는 계정도 같은 비용의 해시 검사를 거치게 해 응답 시간으로 계정 존재 여부를 드러내지 않는다.
  private final String unknownAccountHash;

  public SessionService(
      AccountRepository accounts,
      PasswordHasher passwords,
      LoginRateLimiter rateLimiter,
      SessionStore sessions) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.rateLimiter = rateLimiter;
    this.sessions = sessions;
    this.unknownAccountHash = passwords.hash(UUID.randomUUID().toString());
  }

  /** 요청 제한 → 계정·비밀번호·상태 확인 → 가드와 버전이 맞을 때만 세션 저장 순서다. 실패 사유는 하나의 인증 실패로 묶는다. */
  public Login login(ActorKind kind, String email, String password) {
    String emailNormalized = Account.normalizeEmail(email);
    rateLimiter.acquire(kind, emailNormalized);

    Optional<Account> found = accounts.findByEmail(kind, emailNormalized);
    boolean matches =
        passwords.matches(password, found.map(Account::getPasswordHash).orElse(unknownAccountHash));
    Account account =
        found
            .filter(candidate -> matches && candidate.canLogIn())
            .orElseThrow(AuthenticationFailedException::new);

    IssuedSession session = sessions.issue(kind, account.getId(), account.getAuthVersion());
    return new Login(session.token(), session.expiresAt(), account);
  }

  public void logout(String tokenHash, ActorKind kind, UUID actorId) {
    sessions.delete(tokenHash, kind, actorId);
  }

  public record Login(String token, Instant expiresAt, Account account) {}
}
