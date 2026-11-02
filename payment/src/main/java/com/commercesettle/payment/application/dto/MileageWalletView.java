package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.MileageWallet;
import java.util.Objects;

/** 구매자 마일리지 잔액. 지갑이 없는 구매자는 0점 지갑과 같다. */
public record MileageWalletView(
    long balance, long reservedAmount, long availableAmount, long version) {
  public static final MileageWalletView EMPTY = new MileageWalletView(0, 0, 0, 0);

  public static MileageWalletView of(MileageWallet wallet) {
    return new MileageWalletView(
        wallet.getBalance(),
        wallet.getReservedAmount(),
        wallet.available(),
        Objects.requireNonNullElse(wallet.getVersion(), 0L));
  }
}
