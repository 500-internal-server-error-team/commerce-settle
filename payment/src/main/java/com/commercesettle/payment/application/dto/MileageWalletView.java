package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.MileageWallet;
import java.util.Objects;

/** 구매자 마일리지 잔액. 지갑이 없는 구매자는 0점 지갑과 같다. 적립 예정 합계는 구매확정 전이라 잔액·가용액에 들어가지 않으며 사용할 수 없다. */
public record MileageWalletView(
    long balance,
    long reservedAmount,
    long availableAmount,
    long pendingAccrualAmount,
    long version) {

  public static MileageWalletView empty(long pendingAccrualAmount) {
    return new MileageWalletView(0, 0, 0, pendingAccrualAmount, 0);
  }

  public static MileageWalletView of(MileageWallet wallet, long pendingAccrualAmount) {
    return new MileageWalletView(
        wallet.getBalance(),
        wallet.getReservedAmount(),
        wallet.available(),
        pendingAccrualAmount,
        Objects.requireNonNullElse(wallet.getVersion(), 0L));
  }
}
