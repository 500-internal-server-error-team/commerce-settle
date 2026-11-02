package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.MileageEntryView;
import com.commercesettle.payment.application.dto.MileageWalletView;
import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매자 본인의 마일리지 잔액과 원장을 조회한다. 지갑이 없는 구매자는 0점이고 원장이 없다. */
@Service
public class MileageQueryService {
  private final MileageWalletRepository wallets;
  private final MileageEntryRepository entries;

  public MileageQueryService(MileageWalletRepository wallets, MileageEntryRepository entries) {
    this.wallets = wallets;
    this.entries = entries;
  }

  @Transactional(readOnly = true)
  public MileageWalletView wallet(UUID buyerId) {
    return wallets
        .findByBuyerId(buyerId)
        .map(MileageWalletView::of)
        .orElse(MileageWalletView.EMPTY);
  }

  @Transactional(readOnly = true)
  public PageView<MileageEntryView> entries(UUID buyerId, int page, int size) {
    Optional<UUID> wallet = wallets.findByBuyerId(buyerId).map(MileageWallet::getId);
    long total = wallet.map(entries::countByWalletId).orElse(0L);
    return PageView.read(
        page,
        size,
        total,
        (p, s) ->
            wallet
                .map(id -> entries.findByWalletId(id, p, s))
                .orElseGet(java.util.List::of)
                .stream()
                .map(MileageEntryView::of)
                .toList());
  }
}
