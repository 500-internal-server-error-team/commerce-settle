package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.AuditLog;
import com.commercesettle.commerce.application.port.AuthRateLimiter;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.application.port.PasswordResetDelivery;
import com.commercesettle.commerce.application.service.AccountAuthChanges.Confirmed;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.PasswordResetToken;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.PasswordResetTokenRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 복구 요청과 재설정. 계약은 docs/policies.md#폐기와-저장소-복구와 docs/data-model.md#password_reset_tokens를 따른다.
 */
@Service
public class PasswordResetService {
  private static final int TOKEN_BYTES = 32;
  private static final String REASON = "PASSWORD_RESET";

  private final AccountRepository accounts;
  private final PasswordResetTokenRepository tokens;
  private final PasswordHasher passwords;
  private final AuthRateLimiter rateLimiter;
  private final PasswordResetDelivery delivery;
  private final AccountAuthChanges authChanges;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public PasswordResetService(
      AccountRepository accounts,
      PasswordResetTokenRepository tokens,
      PasswordHasher passwords,
      AuthRateLimiter rateLimiter,
      PasswordResetDelivery delivery,
      AccountAuthChanges authChanges,
      AuditLog audit,
      TransactionTemplate transactions,
      Clock clock) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.passwords = passwords;
    this.rateLimiter = rateLimiter;
    this.delivery = delivery;
    this.authChanges = authChanges;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * 요청 제한 → 로그인할 수 있는 계정이면 계정 잠금 아래 이전 복구 토큰 폐기와 새 토큰 발급 순서다. 계정이 없거나 정지·삭제됐어도 같은 결과로 돌아와 응답으로 계정
   * 존재 여부를 드러내지 않는다.
   */
  public void request(ActorKind kind, String email) {
    String emailNormalized = Account.normalizeEmail(email);
    rateLimiter.acquire(AuthRateLimiter.Operation.PASSWORD_RESET_REQUEST, kind, emailNormalized);
    // 잠금 조회가 앞선 조회의 영속성 컨텍스트 값을 재사용하지 않도록 이메일 조회는 트랜잭션 밖에서 한다.
    accounts
        .findByEmail(kind, emailNormalized)
        .ifPresent(
            found ->
                transactions.executeWithoutResult(
                    status ->
                        accounts
                            .findLockedById(kind, found.getId())
                            .filter(Account::canLogIn)
                            .ifPresent(this::issueToken)));
  }

  /**
   * 토큰·계정 확인 → 새 해시 계산 → 가드 선차단 → 잠금 아래 재확인과 비밀번호 교체·토큰 소비·다른 토큰 폐기·감사 커밋 → 가드 확정 순서다. 차단 전에 쓸 수 없는
   * 토큰은 AuthenticationFailedException이다.
   */
  public void reset(ActorKind kind, String token, String newPassword) {
    String tokenHash = PasswordResetToken.hash(token);
    PasswordResetToken found =
        tokens
            .findByTokenHash(tokenHash)
            .filter(candidate -> candidate.isUsableFor(kind, clock.instant()))
            .orElseThrow(AuthenticationFailedException::new);
    Account account =
        accounts
            .findById(kind, found.getActorId())
            .filter(Account::canLogIn)
            .orElseThrow(AuthenticationFailedException::new);
    // Argon2 계산을 가드 차단 전에 끝내 같은 계정의 세션을 막는 시간을 줄인다.
    String newPasswordHash = passwords.hash(newPassword);

    UUID actorId = account.getId();
    long authVersion = account.getAuthVersion();
    authChanges.apply(
        kind,
        actorId,
        authVersion,
        changeId ->
            transactions.execute(
                status ->
                    changePassword(
                        kind, actorId, authVersion, tokenHash, newPasswordHash, changeId)));
  }

  private void issueToken(Account account) {
    Instant now = clock.instant();
    tokens.findOpenByActor(account.actorKind(), account.getId()).forEach(open -> open.revoke(now));
    UUID id = UUID.randomUUID();
    String token = newToken();
    tokens.save(
        PasswordResetToken.issue(
            id,
            account.actorKind(),
            account.getId(),
            PasswordResetToken.hash(token),
            delivery.seal(id, token),
            now));
  }

  // 차단 전 확인 뒤 다른 요청이 같은 토큰을 쓰거나 인증 버전을 바꿨을 수 있으므로 잠금 아래 다시 확인한다.
  private Confirmed changePassword(
      ActorKind kind,
      UUID actorId,
      long authVersion,
      String tokenHash,
      String newPasswordHash,
      UUID changeId) {
    Instant now = clock.instant();
    Account account =
        accounts
            .findLockedById(kind, actorId)
            .filter(locked -> locked.canLogIn() && locked.getAuthVersion() == authVersion)
            .orElseThrow(AuthenticationFailedException::new);
    PasswordResetToken token =
        tokens
            .findLockedByTokenHash(tokenHash)
            .filter(locked -> locked.isUsableFor(kind, now) && locked.getActorId().equals(actorId))
            .orElseThrow(AuthenticationFailedException::new);

    String beforeStatus = account.getStatus().name();
    account.changePassword(newPasswordHash, now);
    token.consume(now);
    tokens.findOpenByActor(kind, actorId).stream()
        .filter(PasswordResetToken::isOpen)
        .forEach(other -> other.revoke(now));
    audit.record(
        new AuditLog.Entry(
            kind.name(),
            actorId.toString(),
            AuditLog.Action.ACCOUNT_AUTH_CHANGED,
            kind.name() + "_ACCOUNT",
            actorId,
            beforeStatus,
            account.getStatus().name(),
            REASON,
            changeId.toString(),
            now));
    return new Confirmed(account.getAuthVersion(), true);
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
