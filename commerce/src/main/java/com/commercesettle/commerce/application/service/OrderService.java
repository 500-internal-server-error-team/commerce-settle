package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.dto.RegularOrderCommand;
import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.OrderRequestException;
import com.commercesettle.commerce.application.exception.OrderRequestException.Kind;
import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.domain.exception.BuyerAddressNotFoundException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.BuyerAddressRepository;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 일반 주문 접수. 주문·배송지 사본·재고 예약·멱등 기록·결제 요청을 한 트랜잭션으로 저장한다. */
@Service
public class OrderService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final OrderRepository orders;
  private final AccountRepository accounts;
  private final BuyerAddressRepository addresses;
  private final AddressCipher cipher;
  private final InventoryReservationService inventory;
  private final IdempotentRequests idempotency;
  private final OrderEventOutbox outbox;
  private final Clock clock;
  private final long seed;

  public OrderService(
      OrderRepository orders,
      AccountRepository accounts,
      BuyerAddressRepository addresses,
      AddressCipher cipher,
      InventoryReservationService inventory,
      IdempotentRequests idempotency,
      OrderEventOutbox outbox,
      Clock clock,
      @Value("${app.orders.simulation-seed:67}") long seed) {
    this.orders = orders;
    this.accounts = accounts;
    this.addresses = addresses;
    this.cipher = cipher;
    this.inventory = inventory;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.clock = clock;
    this.seed = seed;
  }

  @Transactional
  public OrderView create(UUID buyerId, String key, RegularOrderCommand command) {
    // 주소록 변경도 같은 구매자 행을 먼저 잠그므로 검사와 사본 생성 사이에 주소가 바뀌지 않는다.
    accounts
        .findLockedById(ActorKind.BUYER, buyerId)
        .filter(Account::canLogIn)
        .orElseThrow(AuthenticationFailedException::new);
    UUID orderId = UUID.randomUUID();
    return idempotency.executePermanent(
        new IdempotentRequests.Request(
            "BUYER",
            buyerId.toString(),
            "ORDER_CREATE",
            key,
            Arrays.asList(
                command.productId(),
                command.quantity(),
                command.eventId(),
                command.buyerCouponId(),
                command.mileageAmount(),
                command.addressId(),
                command.expectedProductVersion(),
                command.expectedEventVersion(),
                command.expectedTotalAmount(),
                command.externalMethod(),
                command.methodAccountRef())),
        orderId,
        201,
        recordId -> createNew(buyerId, orderId, command),
        existingId -> OrderView.from(owned(buyerId, existingId)));
  }

  private OrderView createNew(UUID buyerId, UUID orderId, RegularOrderCommand command) {
    validateRegular(command);
    var address =
        addresses
            .findById(command.addressId())
            .filter(a -> !a.isDeleted() && a.isOwnedBy(buyerId))
            .orElseThrow(() -> new BuyerAddressNotFoundException(command.addressId()));
    // 상품은 이 트랜잭션에서 처음 읽는다. 상품 변경도 재고 행을 먼저 잠근다.
    var product = inventory.lockInventoryAndReadProduct(command.productId());
    if (!product.isPubliclyVisible()) {
      throw new ProductNotFoundException(command.productId());
    }
    if (product.getVersion() != command.expectedProductVersion()) {
      throw new VersionConflictException(
          "product", command.expectedProductVersion(), product.getVersion());
    }
    long total = product.getUnitPrice() * command.quantity();
    if (total > 10_000_000) {
      throw invalid("quantity");
    }
    if (total != command.expectedTotalAmount()) {
      throw new VersionConflictException("totalAmount", command.expectedTotalAmount(), total);
    }
    if (command.mileageAmount() < 0 || command.mileageAmount() > total) {
      throw invalid("mileageAmount");
    }
    boolean external = command.mileageAmount() < total;
    if (external && command.externalMethod() == null
        || !external && command.externalMethod() != null) {
      throw invalid("externalMethod");
    }
    String account = command.methodAccountRef();
    if (external && (account == null || account.isBlank() || account.length() > 100)
        || !external && account != null) {
      throw invalid("methodAccountRef");
    }
    var now = clock.instant();
    Order order =
        Order.pendingRegular(
            orderId,
            buyerId,
            product,
            command.quantity(),
            command.mileageAmount(),
            command.externalMethod() == null
                ? null
                : Order.ExternalMethod.valueOf(command.externalMethod().name()),
            account,
            seed,
            "weekdays-v1",
            List.of(),
            "order-regular-v1",
            now);
    UUID snapshotId = UUID.randomUUID();
    OrderAddress snapshot =
        OrderAddress.snapshot(
            snapshotId,
            orderId,
            cipher.seal(snapshotId, cipher.open(address.getId(), address.getSealed())),
            now);
    Order saved = orders.save(order, snapshot);
    inventory.reserve(
        orderId,
        product.getId(),
        command.quantity(),
        order.getPaymentCycleId(),
        order.getPaymentWaitUntil());
    var amounts = saved.getAmounts();
    var payload =
        new OrderPaymentRequestedPayload(
            orderId,
            buyerId,
            saved.getSellerId(),
            saved.getPaymentCycleId(),
            product.getId(),
            saved.getVersion(),
            OrderPaymentRequestedPayload.Status.PAYMENT_PENDING,
            saved.getPolicyVersion(),
            saved.getPaymentWaitUntil().atZone(SEOUL).toOffsetDateTime(),
            saved.getExternalMethod() == null
                ? null
                : OrderPaymentRequestedPayload.ExternalMethod.valueOf(
                    saved.getExternalMethod().name()),
            account,
            amounts.grossAmount(),
            amounts.couponAmount(),
            amounts.totalAmount(),
            amounts.mileageAmount(),
            amounts.externalAmount(),
            amounts.sellerCouponAmount(),
            amounts.platformCouponAmount(),
            amounts.settlementBaseAmount());
    outbox.appendPaymentRequested(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "OrderPaymentRequested",
            1,
            saved.getCreatedAt().atZone(SEOUL).toOffsetDateTime(),
            orderId,
            saved.getVersion(),
            payload));
    return OrderView.from(saved);
  }

  @Transactional(readOnly = true)
  public OrderView get(UUID buyerId, UUID orderId) {
    requireBuyer(buyerId);
    return OrderView.from(owned(buyerId, orderId));
  }

  @Transactional(readOnly = true)
  public PageResult<OrderView> list(UUID buyerId, int page, int size) {
    requireBuyer(buyerId);
    if (page < 0 || size < 1 || size > 100) {
      throw invalid("page");
    }
    var found = orders.findByBuyer(buyerId, page, size);
    return new PageResult<>(
        found.items().stream().map(OrderView::from).toList(),
        found.page(),
        found.size(),
        found.totalElements());
  }

  private Order owned(UUID buyerId, UUID orderId) {
    return orders
        .findByBuyerAndId(buyerId, orderId)
        .orElseThrow(() -> new OrderRequestException(Kind.NOT_FOUND, "orderId"));
  }

  private void requireBuyer(UUID buyerId) {
    accounts
        .findById(ActorKind.BUYER, buyerId)
        .filter(Account::canLogIn)
        .orElseThrow(AuthenticationFailedException::new);
  }

  private static void validateRegular(RegularOrderCommand c) {
    if (c.eventId() != null) {
      throw invalid("eventId");
    }
    if (c.buyerCouponId() != null) {
      throw invalid("buyerCouponId");
    }
    if (c.expectedEventVersion() != null) {
      throw invalid("expectedEventVersion");
    }
    if (c.productId() == null) {
      throw invalid("productId");
    }
    if (c.addressId() == null) {
      throw invalid("addressId");
    }
    if (c.quantity() < 1 || c.quantity() > 10) {
      throw invalid("quantity");
    }
    if (c.expectedProductVersion() < 0) {
      throw invalid("expectedProductVersion");
    }
    if (c.expectedTotalAmount() < 1 || c.expectedTotalAmount() > 10_000_000) {
      throw invalid("expectedTotalAmount");
    }
  }

  private static OrderRequestException invalid(String field) {
    return new OrderRequestException(Kind.INVALID_REQUEST, field);
  }
}
