package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  Payment save(Payment payment);

  Optional<Payment> findById(UUID id);

  Optional<Payment> findByOrderId(UUID orderId);

  /** 구매자 본인의 결제만 찾는다. 다른 구매자의 결제는 없는 것과 같다. */
  Optional<Payment> findByIdAndBuyerId(UUID id, UUID buyerId);

  /** 구매자의 결제를 생성 시각·ID 내림차순으로 한 페이지 읽는다. orderId가 있으면 그 주문의 결제만 읽는다. */
  List<Payment> findByBuyer(UUID buyerId, UUID orderId, int page, int size);

  long countByBuyer(UUID buyerId, UUID orderId);
}
