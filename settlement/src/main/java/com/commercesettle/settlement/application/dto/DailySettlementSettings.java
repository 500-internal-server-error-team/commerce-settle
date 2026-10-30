package com.commercesettle.settlement.application.dto;

import java.util.Objects;

/**
 * 일배치가 배치·지급에 고정하는 값.
 *
 * @param policyVersion 배치 업무 키에 들어가는 정책 버전
 * @param fundingAccountRef 지급을 출금할 PAYOUT 재원 계정
 * @param payoutScenarioVersion 지급 대역 호출에 고정할 금융 실행 시나리오 버전
 */
public record DailySettlementSettings(
    String policyVersion, String fundingAccountRef, String payoutScenarioVersion) {
  public DailySettlementSettings {
    Objects.requireNonNull(policyVersion, "policyVersion");
    Objects.requireNonNull(fundingAccountRef, "fundingAccountRef");
    Objects.requireNonNull(payoutScenarioVersion, "payoutScenarioVersion");
  }
}
