package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "seller_accounts")
public class SellerAccount extends Account {
  // 시험용 지급 계정 참조. 실계좌 정보가 아니다.
  private String payoutAccountRef;

  protected SellerAccount() {}

  private SellerAccount(
      String email, String passwordHash, String displayName, String payoutAccountRef, Instant now) {
    super(email, passwordHash, displayName, now);
    this.payoutAccountRef = Objects.requireNonNull(payoutAccountRef, "payoutAccountRef");
  }

  public static SellerAccount register(
      String email, String passwordHash, String displayName, String payoutAccountRef, Instant now) {
    return new SellerAccount(email, passwordHash, displayName, payoutAccountRef, now);
  }

  /**
   * 표시명과 시험 수취 계정을 바꾼다. 이미 만든 지급은 생성 당시의 계정 사본을 쓰므로 이 변경의 영향을 받지 않는다. 판매 상태와 인증 버전은 바꾸지 않는다.
   *
   * @throws VersionConflictException 기대 버전이 현재 버전과 다르면
   */
  public void changeProfile(
      String displayName, String payoutAccountRef, long expectedVersion, Instant now) {
    if (!canLogIn()) {
      throw new IllegalStateException("Only active sellers can change profile");
    }
    if (expectedVersion != getVersion()) {
      throw new VersionConflictException("seller profile", expectedVersion, getVersion());
    }
    changeDisplayName(displayName);
    this.payoutAccountRef = Objects.requireNonNull(payoutAccountRef, "payoutAccountRef");
    touch(now);
  }

  @Override
  public ActorKind actorKind() {
    return ActorKind.SELLER;
  }

  public String getPayoutAccountRef() {
    return payoutAccountRef;
  }
}
