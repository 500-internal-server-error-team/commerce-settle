package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.domain.model.MileageAccrualPolicy;
import java.util.Optional;

/** 새 주문에 고정할 현재 구매 적립 정책. 꺼져 있으면 새 주문은 정책 사본 없이 0점이다. */
public final class MileageAccrualSettings {
  private final MileageAccrualPolicy policy;

  private MileageAccrualSettings(MileageAccrualPolicy policy) {
    this.policy = policy;
  }

  public static MileageAccrualSettings enabled(MileageAccrualPolicy policy) {
    return new MileageAccrualSettings(java.util.Objects.requireNonNull(policy, "policy"));
  }

  public static MileageAccrualSettings disabled() {
    return new MileageAccrualSettings(null);
  }

  public Optional<MileageAccrualPolicy> current() {
    return Optional.ofNullable(policy);
  }
}
