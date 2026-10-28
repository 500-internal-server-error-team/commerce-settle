package com.commercesettle.settlement.domain.model;

import com.commercesettle.settlement.domain.exception.InvalidSettlementAmountException;

/**
 * Payment가 확정한 T·P·B·E·M·수수료·판매자 몫의 사본. 다시 계산하지 않고 금액 불변식만 검사한다.
 *
 * <p>완료된 거래는 현재 요율로 다시 계산하지 않으므로 수수료율은 검사하지 않는다.
 */
public record SettlementAmounts(
    long totalAmount,
    long platformCouponAmount,
    long settlementBaseAmount,
    long externalAmount,
    long mileageAmount,
    long feeAmount,
    long netAmount) {

  public SettlementAmounts {
    require(totalAmount >= 0, "결제 총액은 0 이상이어야 합니다.");
    require(platformCouponAmount >= 0, "플랫폼 쿠폰액은 0 이상이어야 합니다.");
    require(settlementBaseAmount >= 0, "정산 기준액은 0 이상이어야 합니다.");
    require(externalAmount >= 0, "외부 결제액은 0 이상이어야 합니다.");
    require(mileageAmount >= 0, "마일리지 사용액은 0 이상이어야 합니다.");
    require(feeAmount >= 0, "수수료는 0 이상이어야 합니다.");
    require(netAmount >= 0, "판매자 몫은 0 이상이어야 합니다.");
    require(totalAmount == externalAmount + mileageAmount, "결제 총액은 외부 결제액과 마일리지의 합이어야 합니다.");
    require(
        settlementBaseAmount == totalAmount + platformCouponAmount,
        "정산 기준액은 결제 총액과 플랫폼 쿠폰액의 합이어야 합니다.");
    require(settlementBaseAmount == feeAmount + netAmount, "정산 기준액은 수수료와 판매자 몫의 합이어야 합니다.");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new InvalidSettlementAmountException(message);
    }
  }
}
