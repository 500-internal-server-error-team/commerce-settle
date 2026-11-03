package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentNoticeRequestedPayload;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 결제 확정·마일리지 복원과 같은 트랜잭션에서 기존 알림 계약을 Outbox에 저장한다. */
@Component
public class PaymentNotices {
  private final PaymentEventOutbox outbox;
  private final MileageWalletRepository wallets;

  PaymentNotices(PaymentEventOutbox outbox, MileageWalletRepository wallets) {
    this.outbox = outbox;
    this.wallets = wallets;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void completed(
      Payment payment, UUID refundId, MileageWallet wallet, Instant now, long aggregateVersion) {
    if ((payment.getAmounts().mileageAmount() > 0) != (wallet != null)
        || (wallet != null && !payment.getBuyerId().equals(wallet.getBuyerId()))) {
      throw new IllegalStateException("Mileage notice requires the changed wallet");
    }
    // JPA의 버전 증가를 추정하지 않고 실제 갱신된 버전을 사본에 넣는다. flush 뒤에도 롤백에 참여한다.
    if (wallet != null) {
      wallet = wallets.saveAndFlush(wallet);
    }
    var processedAt = now.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    UUID businessRef = refundId == null ? payment.getId() : refundId;
    String kind = refundId == null ? "PAYMENT_COMPLETED" : "REFUND_COMPLETED";
    var amounts = payment.getAmounts();
    var payload =
        new PaymentNoticeRequestedPayload(
            kind,
            businessRef,
            payment.getId(),
            payment.getCurrentCycleId(),
            payment.getOrderId(),
            payment.getBuyerId(),
            payment.getSellerId(),
            refundId,
            amounts.totalAmount(),
            amounts.externalAmount(),
            amounts.mileageAmount(),
            wallet == null ? null : wallet.getBalance(),
            wallet == null ? null : wallet.getVersion(),
            processedAt);
    outbox.appendNoticeRequested(
        new EventEnvelope<>(
            UUID.nameUUIDFromBytes(
                ("PaymentNoticeRequested:" + kind + ":" + businessRef)
                    .getBytes(StandardCharsets.UTF_8)),
            "PaymentNoticeRequested",
            1,
            processedAt,
            payment.getId(),
            aggregateVersion,
            payload));
  }
}
