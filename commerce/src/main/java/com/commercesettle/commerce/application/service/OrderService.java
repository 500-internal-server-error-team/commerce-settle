package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.dto.RegularOrderCommand;
import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.OrderRequestException;
import com.commercesettle.commerce.application.exception.OrderRequestException.Kind;
import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.domain.exception.BuyerAddressNotFoundException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerAddress;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import com.commercesettle.commerce.domain.model.OrderAmounts;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.BuyerAddressRepository;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
  private final OrderBenefits benefits;
  private final ProductRepository products;
  private final OrderAdmissionService admission;
  private final EventOrderConcurrencyLimiter eventSlots;
  private final TransactionTemplate transactions;
  private final TransactionTemplate replayReads;

  public OrderService(
      OrderRepository orders,
      AccountRepository accounts,
      BuyerAddressRepository addresses,
      AddressCipher cipher,
      InventoryReservationService inventory,
      IdempotentRequests idempotency,
      OrderEventOutbox outbox,
      Clock clock,
      OrderBenefits benefits,
      ProductRepository products,
      OrderAdmissionService admission,
      EventOrderConcurrencyLimiter eventSlots,
      PlatformTransactionManager manager,
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
    this.benefits = benefits;
    this.products = products;
    this.admission = admission;
    this.eventSlots = eventSlots;
    this.transactions = new TransactionTemplate(manager);
    this.replayReads = new TransactionTemplate(manager);
    this.replayReads.setReadOnly(true);
  }

  /**
   * 일반 주문은 바로 주문 트랜잭션을 실행한다. 신규 선착순 이벤트 주문은 짧은 읽기 트랜잭션에서 기존 멱등 결과·본문 충돌과 신규 거절(접수 제한·입력·매진/종료)을 먼저
   * 판정한 뒤, DB 커넥션 없이 이벤트별 동시 실행 슬롯을 받고 주문 트랜잭션을 실행한다. 슬롯은 트랜잭션이 끝난 뒤 반납한다.
   */
  @Transactional(propagation = Propagation.NEVER)
  public OrderView create(UUID buyerId, String key, RegularOrderCommand command) {
    if (command.eventId() == null)
      return transactions.execute(status -> createInTransaction(buyerId, key, command));
    var prior = replayReads.execute(status -> precheckEventOrder(buyerId, key, command));
    if (prior.isPresent()) return prior.get();
    try (var permit = eventSlots.acquire(command.eventId())) {
      return transactions.execute(status -> createInTransaction(buyerId, key, command));
    }
  }

  /**
   * 기존 완료 기록이 있으면 그 결과를 돌려주고 새 키는 저장하지 않는다. 신규 요청은 주문 트랜잭션과 같은 순서로 접수 제한·입력·이벤트 마감을 판정해, 거절될 요청이
   * 슬롯을 기다리지 않게 한다. 슬롯을 받은 뒤 주문 트랜잭션이 모두 다시 판정하며, 같은 키의 동시 신규 요청도 그 멱등 판정이 막는다.
   */
  private Optional<OrderView> precheckEventOrder(
      UUID buyerId, String key, RegularOrderCommand command) {
    accounts
        .findById(ActorKind.BUYER, buyerId)
        .filter(Account::canLogIn)
        .orElseThrow(AuthenticationFailedException::new);
    var prior =
        idempotency.replayPermanent(
            createRequest(buyerId, key, command),
            existingId -> OrderView.from(owned(buyerId, existingId)));
    if (prior.isPresent()) return prior;
    admission.requireAdmission();
    validateRegular(command);
    benefits.requireEventOpen(command.eventId());
    return Optional.empty();
  }

  private OrderView createInTransaction(UUID buyerId, String key, RegularOrderCommand command) {
    // 주소록 변경도 같은 구매자 행을 먼저 잠그므로 검사와 사본 생성 사이에 주소가 바뀌지 않는다.
    accounts
        .findLockedById(ActorKind.BUYER, buyerId)
        .filter(Account::canLogIn)
        .orElseThrow(AuthenticationFailedException::new);
    UUID orderId = UUID.randomUUID();
    return idempotency.executePermanent(
        createRequest(buyerId, key, command),
        orderId,
        201,
        recordId -> createNew(buyerId, orderId, command),
        existingId -> OrderView.from(owned(buyerId, existingId)));
  }

  private static IdempotentRequests.Request createRequest(
      UUID buyerId, String key, RegularOrderCommand command) {
    return new IdempotentRequests.Request(
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
            command.methodAccountRef()));
  }

  private OrderView createNew(UUID buyerId, UUID orderId, RegularOrderCommand command) {
    admission.requireAdmission();
    validateRegular(command);
    // 매진·종료된 이벤트는 멱등 기록 뒤, 주문·주소 사본을 만들기 전에 거절한다. 같은 키의 기존 주문 재요청은 위에서 원래 결과를 돌려받는다.
    if (command.eventId() != null) benefits.requireEventOpen(command.eventId());
    var address =
        addresses
            .findById(command.addressId())
            .filter(a -> !a.isDeleted() && a.isOwnedBy(buyerId))
            .orElseThrow(() -> new BuyerAddressNotFoundException(command.addressId()));
    Order saved =
        command.eventId() == null
            ? inventory.createAndReserve(
                command.productId(),
                product -> createOrder(buyerId, orderId, command, address, product))
            : createOrder(
                buyerId,
                orderId,
                command,
                address,
                products
                    .findById(command.productId())
                    .orElseThrow(() -> new ProductNotFoundException(command.productId())));
    appendPaymentRequest(saved);
    return OrderView.from(saved);
  }

  private Order createOrder(
      UUID buyerId,
      UUID orderId,
      RegularOrderCommand command,
      BuyerAddress address,
      Product product) {
    if (!product.isPubliclyVisible()) {
      throw new ProductNotFoundException(command.productId());
    }
    if (product.getVersion() != command.expectedProductVersion()) {
      throw new VersionConflictException(
          "product", command.expectedProductVersion(), product.getVersion());
    }
    // 쿠폰 기한·이벤트 기간은 주문 생성 시각 하나로 판단해 견적과 예약이 서로 다른 시각으로 갈리지 않게 한다.
    var now = clock.instant();
    var offer =
        benefits.quote(
            buyerId,
            product.getId(),
            product.getSellerId(),
            product.getUnitPrice(),
            command.quantity(),
            command.buyerCouponId(),
            command.eventId(),
            command.externalMethod() == null
                ? null
                : Order.ExternalMethod.valueOf(command.externalMethod().name()),
            now);
    if (!java.util.Objects.equals(command.expectedEventVersion(), offer.eventVersion())) {
      throw new VersionConflictException(
          "event",
          command.expectedEventVersion() == null ? -1 : command.expectedEventVersion(),
          offer.eventVersion() == null ? -1 : offer.eventVersion());
    }
    long gross = Math.multiplyExact(offer.unitPrice(), command.quantity());
    if (gross > 10_000_000) throw invalid("quantity");
    long payable = gross - offer.sellerCouponAmount() - offer.platformCouponAmount();
    if (command.mileageAmount() < 0 || command.mileageAmount() > payable)
      throw invalid("mileageAmount");
    var calculated =
        new OrderAmounts(
            gross,
            offer.sellerCouponAmount(),
            offer.platformCouponAmount(),
            command.mileageAmount());
    long total = calculated.totalAmount();
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
    Order order =
        Order.pendingOffer(
            orderId,
            buyerId,
            product,
            command.quantity(),
            command.eventId(),
            command.buyerCouponId(),
            offer.unitPrice(),
            calculated,
            command.externalMethod() == null
                ? null
                : Order.ExternalMethod.valueOf(command.externalMethod().name()),
            account,
            seed,
            now);
    UUID snapshotId = UUID.randomUUID();
    OrderAddress snapshot =
        OrderAddress.snapshot(
            snapshotId,
            orderId,
            cipher.seal(snapshotId, cipher.open(address.getId(), address.getSealed())),
            now);
    Order saved = orders.save(order, snapshot);
    benefits.reserve(saved, command.expectedEventVersion(), now);
    return saved;
  }

  private void appendPaymentRequest(Order saved) {
    var amounts = saved.getAmounts();
    var payload =
        new OrderPaymentRequestedPayload(
            saved.getId(),
            saved.getBuyerId(),
            saved.getSellerId(),
            saved.getPaymentCycleId(),
            saved.getProductId(),
            saved.getVersion(),
            OrderPaymentRequestedPayload.Status.PAYMENT_PENDING,
            saved.getPolicyVersion(),
            saved.getPaymentWaitUntil().atZone(SEOUL).toOffsetDateTime(),
            saved.getExternalMethod() == null
                ? null
                : OrderPaymentRequestedPayload.ExternalMethod.valueOf(
                    saved.getExternalMethod().name()),
            saved.getMethodAccountRef(),
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
            saved.getId(),
            saved.getVersion(),
            payload),
        saved.getCreatedAt());
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
    if ((c.eventId() == null) != (c.expectedEventVersion() == null)
        || c.eventId() != null && (c.quantity() != 1 || c.expectedEventVersion() < 0))
      throw invalid("eventId");
    if (c.buyerCouponId() != null && c.mileageAmount() != 0)
      throw OrderRequestException.couponWithMileage();
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
