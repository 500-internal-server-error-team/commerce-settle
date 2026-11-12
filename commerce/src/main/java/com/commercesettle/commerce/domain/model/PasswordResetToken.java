package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 10분·일회용 비밀번호 복구 토큰. 원문은 저장하지 않고 SHA-256 해시와 전달용 암호문만 둔다. 세션 토큰으로 쓸 수 없다. */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends AuditableEntity {
  public static final Duration LIFETIME = Duration.ofMinutes(10);
  private static final String PURPOSE = "PASSWORD_RESET";
  private static final String DELIVERY_PENDING = "PENDING";

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  private ActorKind actorKind;

  private UUID actorId;
  private String purpose;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 64)
  private String tokenHash;

  private Instant expiresAt;
  private Instant consumedAt;
  private Instant revokedAt;
  private byte[] deliveryCiphertext;
  private String deliveryStatus;
  @Version private Long version;

  protected PasswordResetToken() {}

  /** 전달 작업이 꺼낼 암호문을 PENDING으로 함께 저장한다. */
  public static PasswordResetToken issue(
      UUID id,
      ActorKind actorKind,
      UUID actorId,
      String tokenHash,
      byte[] deliveryCiphertext,
      Instant now) {
    PasswordResetToken token = new PasswordResetToken(now);
    token.id = Objects.requireNonNull(id, "id");
    token.actorKind = Objects.requireNonNull(actorKind, "actorKind");
    token.actorId = Objects.requireNonNull(actorId, "actorId");
    token.purpose = PURPOSE;
    token.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
    token.expiresAt = token.getCreatedAt().plus(LIFETIME);
    token.deliveryCiphertext = Objects.requireNonNull(deliveryCiphertext, "deliveryCiphertext");
    token.deliveryStatus = DELIVERY_PENDING;
    return token;
  }

  private PasswordResetToken(Instant now) {
    super(now);
  }

  /** 토큰(UTF-8)의 SHA-256 소문자 16진수. 조회와 저장이 같은 값을 쓴다. */
  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /** 같은 계정 종류의 복구 목적이고 만료 전이며 소비·폐기되지 않았을 때만 쓸 수 있다. */
  public boolean isUsableFor(ActorKind kind, Instant now) {
    return actorKind == kind
        && PURPOSE.equals(purpose)
        && now.isBefore(expiresAt)
        && consumedAt == null
        && revokedAt == null;
  }

  public void consume(Instant now) {
    requireOpen();
    consumedAt = truncate(now);
    touch(now);
  }

  public void revoke(Instant now) {
    requireOpen();
    revokedAt = truncate(now);
    touch(now);
  }

  public boolean isOpen() {
    return consumedAt == null && revokedAt == null;
  }

  private void requireOpen() {
    if (!isOpen()) {
      throw new IllegalStateException("Password reset token is already closed");
    }
  }

  public UUID getId() {
    return id;
  }

  public ActorKind getActorKind() {
    return actorKind;
  }

  public UUID getActorId() {
    return actorId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
