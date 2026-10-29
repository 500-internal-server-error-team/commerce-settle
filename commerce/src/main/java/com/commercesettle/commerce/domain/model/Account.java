package com.commercesettle.commerce.domain.model;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 구매자·판매자 계정의 공통 상태. authVersion은 인증에 영향을 주는 변경에만 올라 Redis 가드와 대조하며, 동시 수정을 막는 version과 다르다. */
@MappedSuperclass
public abstract class Account extends AuditableEntity {
  @Id private UUID id;
  private String emailNormalized;
  private String passwordHash;
  private String displayName;

  @Enumerated(EnumType.STRING)
  private AccountStatus status;

  private long authVersion;
  @Version private Long version;
  private Instant deletedAt;

  protected Account() {}

  protected Account(String email, String passwordHash, String displayName, Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.emailNormalized = normalizeEmail(email);
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    this.displayName = Objects.requireNonNull(displayName, "displayName");
    this.status = AccountStatus.ACTIVE;
  }

  /** 가입·로그인·요청 제한이 같은 키를 쓰도록 앞뒤 공백을 지우고 소문자로 맞춘다. */
  public static String normalizeEmail(String email) {
    return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
  }

  public abstract ActorKind actorKind();

  public boolean canLogIn() {
    return status == AccountStatus.ACTIVE;
  }

  /** 비밀번호를 바꾸고 인증 버전을 올려 이전 버전의 세션을 모두 무효로 만든다. */
  public void changePassword(String newPasswordHash, Instant now) {
    if (!canLogIn()) {
      throw new IllegalStateException("Only active accounts can change password");
    }
    this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash");
    this.authVersion++;
    touch(now);
  }

  public UUID getId() {
    return id;
  }

  public String getEmailNormalized() {
    return emailNormalized;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public AccountStatus getStatus() {
    return status;
  }

  public long getAuthVersion() {
    return authVersion;
  }

  public Long getVersion() {
    return version;
  }
}
