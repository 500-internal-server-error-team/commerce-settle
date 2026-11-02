package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.OrderRefundView;
import com.commercesettle.commerce.application.event.OrderLifecycleEvents;
import com.commercesettle.commerce.application.port.AuditLog;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.application.port.RefundClosureEvidence;
import com.commercesettle.commerce.application.port.RefundClosureStore;
import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import com.commercesettle.commerce.domain.repository.OrderRefundRepository;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 요청 변경은 같은 주문 행을 먼저 잠근다. 금융 조회는 트랜잭션 밖에서 수행한다. */
@Service
public class OrderRefundService {
  private final OrderRepository orders;
  private final OrderRefundRepository requests;
  private final OrderWorkflowService workflow;
  private final InventoryReservationService inventory;
  private final IdempotentRequests keys;
  private final AuditLog audit;
  private final RefundClosureEvidence closure;
  private final RefundClosureStore closureStore;
  private final OrderBenefits benefits;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final NotificationService notifications;

  public OrderRefundService(
      OrderRepository orders,
      OrderRefundRepository requests,
      OrderWorkflowService workflow,
      InventoryReservationService inventory,
      IdempotentRequests keys,
      AuditLog audit,
      RefundClosureEvidence closure,
      RefundClosureStore closureStore,
      OrderBenefits benefits,
      PlatformTransactionManager manager,
      Clock clock,
      NotificationService notifications) {
    this.orders = orders;
    this.requests = requests;
    this.workflow = workflow;
    this.inventory = inventory;
    this.keys = keys;
    this.audit = audit;
    this.closure = closure;
    this.closureStore = closureStore;
    this.benefits = benefits;
    this.clock = clock;
    this.notifications = notifications;
    transactions = new TransactionTemplate(manager);
  }

  @Transactional
  public OrderRefundView create(
      UUID buyer,
      UUID orderId,
      String key,
      long expected,
      OrderRefundRequest.Kind kind,
      String reason) {
    workflow.requireAccount(ActorKind.BUYER, buyer);
    UUID id = UUID.randomUUID();
    return keys.executePermanent(
        OrderWorkflowService.request(
            "BUYER", buyer, "CREATE_REFUND_REQUEST", key, List.of(orderId, expected, kind, reason)),
        id,
        201,
        ignored -> {
          var order = workflow.ownedLocked(ActorKind.BUYER, buyer, orderId);
          order.requireVersion(expected);
          var before = order.getStatus();
          var deliveredBefore = order.getDeliveredAt();
          order.beginRefund(kind == OrderRefundRequest.Kind.CANCEL, clock.instant());
          orders.save(order);
          if (deliveredBefore == null && order.getDeliveredAt() != null)
            notifications.order(
                order,
                com.commercesettle.commerce.domain.model.Notification.Kind.SHIPMENT_DELIVERED,
                null,
                order.getDeliveredAt());
          var r =
              requests.save(OrderRefundRequest.create(id, order, kind, reason, clock.instant()));
          workflow.changed(order, before, "BUYER", buyer.toString(), "REFUND_REQUESTED", null);
          audit(r, null, "BUYER", buyer.toString(), "REFUND_REQUESTED");
          notifications.refund(
              order,
              r,
              com.commercesettle.commerce.domain.model.Notification.Kind.RETURN_REQUESTED,
              null,
              r.getAcceptedAt());
          if (kind == OrderRefundRequest.Kind.CANCEL) approved(order, r);
          return OrderRefundView.from(r);
        },
        existing -> getOwned(ActorKind.BUYER, buyer, existing));
  }

  @Transactional(readOnly = true)
  public OrderRefundView get(UUID buyer, UUID id) {
    workflow.requireAccount(ActorKind.BUYER, buyer);
    return getOwned(ActorKind.BUYER, buyer, id);
  }

  private OrderRefundView getOwned(ActorKind kind, UUID actor, UUID id) {
    return OrderRefundView.from(
        requests
            .findById(id)
            .filter(r -> (kind == ActorKind.BUYER ? r.getBuyerId() : r.getSellerId()).equals(actor))
            .orElseThrow(OrderWorkflowService::notFound));
  }

  @Transactional(readOnly = true)
  public PageResult<OrderRefundView> sellerList(UUID seller, int page, int size) {
    workflow.requireAccount(ActorKind.SELLER, seller);
    if (page < 0 || size < 1 || size > 100) throw OrderWorkflowService.invalid("page");
    var p = requests.findBySeller(seller, page, size);
    return new PageResult<>(
        p.items().stream().map(OrderRefundView::from).toList(), page, size, p.totalElements());
  }

  @Transactional
  public OrderRefundView withdraw(UUID buyer, UUID id, String key, long expected) {
    return change(
        ActorKind.BUYER,
        buyer,
        id,
        key,
        expected,
        "WITHDRAW_REFUND",
        List.of(),
        (order, r) -> {
          r.withdraw(clock.instant());
          order.resumeAfterRefund(clock.instant());
          return true;
        });
  }

  @Transactional
  public OrderRefundView decide(
      UUID seller, UUID id, String key, long expected, boolean approve, String reason) {
    return change(
        ActorKind.SELLER,
        seller,
        id,
        key,
        expected,
        "DECIDE_REFUND",
        List.of(approve, reason),
        (order, r) -> {
          r.decide(approve, clock.instant());
          notifications.refund(
              order,
              r,
              com.commercesettle.commerce.domain.model.Notification.Kind.RETURN_DECIDED,
              null,
              r.getDecisionAt());
          if (approve) approved(order, r);
          else order.resumeAfterRefund(clock.instant());
          return true;
        });
  }

  @Transactional
  public OrderRefundView receive(UUID seller, UUID id, String key, long expected) {
    return change(
        ActorKind.SELLER,
        seller,
        id,
        key,
        expected,
        "RECEIVE_RETURN",
        List.of(),
        (order, r) -> {
          boolean received = r.receive(clock.instant());
          if (received) {
            order.receiveReturn(clock.instant());
            if (order.getEventId() == null) inventory.restoreForReturn(order.getId(), r.getId());
            else benefits.receiveReturn(order, r.getId(), clock.instant());
            notifications.refund(
                order,
                r,
                com.commercesettle.commerce.domain.model.Notification.Kind.RETURN_RECEIVED,
                null,
                r.getReceivedAt());
          }
          return received;
        });
  }

  private OrderRefundView change(
      ActorKind kind,
      UUID actor,
      UUID id,
      String key,
      long expected,
      String operation,
      List<?> values,
      java.util.function.BiPredicate<Order, OrderRefundRequest> transition) {
    workflow.requireAccount(kind, actor);
    var body = new java.util.ArrayList<Object>(List.of(id, expected));
    body.addAll(values);
    return keys.executePermanent(
        OrderWorkflowService.request(kind.name(), actor, operation, key, body),
        id,
        operation.equals("DECIDE_REFUND") ? 202 : 200,
        ignored -> {
          var orderId = requests.findOrderId(id).orElseThrow(OrderWorkflowService::notFound);
          var order = workflow.ownedLocked(kind, actor, orderId);
          // 주문 잠금 뒤 최신 요청을 읽는다.
          var r = requests.findById(id).orElseThrow(OrderWorkflowService::notFound);
          r.requireVersion(expected);
          var before = r.getStatus();
          var orderBefore = order.getStatus();
          if (transition.test(order, r)) {
            orders.save(order);
            requests.save(r);
            audit(r, before, kind.name(), actor.toString(), operation);
            workflow.changed(order, orderBefore, kind.name(), actor.toString(), operation, null);
          }
          return OrderRefundView.from(r);
        },
        replay -> getOwned(kind, actor, replay));
  }

  /** 외부 조회 중에는 트랜잭션·주문 잠금을 유지하지 않는다. */
  @Transactional(propagation = Propagation.NEVER)
  public OrderRefundView close(
      String operator, UUID id, String key, long expected, String reason, String customer) {
    var request =
        new IdempotentRequests.Request(
            "OPERATOR", operator, "CLOSE_REFUND", key, List.of(id, expected, reason, customer));
    var priorResult =
        transactions.execute(
            status ->
                keys.replayPermanent(
                    request,
                    existing ->
                        OrderRefundView.from(
                            requests
                                .findById(existing)
                                .orElseThrow(OrderWorkflowService::notFound))));
    if (priorResult != null && priorResult.isPresent()) return priorResult.get();
    var first = requests.findById(id).orElseThrow(OrderWorkflowService::notFound);
    first.requireVersion(expected);
    if (first.getStatus() != OrderRefundRequest.Status.FAILED_REVIEW
        || first.getReceivedAt() != null) throw conflict();
    var firstOrder =
        orders.findById(first.getOrderId()).orElseThrow(OrderWorkflowService::notFound);
    var subject = subject(first, firstOrder);
    var evidence = closure.verify(subject, customer);
    if (!evidence.safe() || !java.util.Objects.equals(first.getRefundId(), evidence.refundId()))
      throw conflict();
    return transactions.execute(
        status ->
            keys.executePermanent(
                request,
                id,
                200,
                ignored -> {
                  var o =
                      orders
                          .findLockedById(first.getOrderId())
                          .orElseThrow(OrderWorkflowService::notFound);
                  var r = requests.findById(id).orElseThrow(OrderWorkflowService::notFound);
                  r.requireVersion(expected);
                  o.requireVersion(subject.orderVersion());
                  if (!java.util.Objects.equals(r.getRefundId(), evidence.refundId()))
                    throw conflict();
                  var before = r.getStatus();
                  var orderBefore = o.getStatus();
                  r.close(reason, evidence.reference(), clock.instant());
                  closureStore.preserve(
                      UUID.fromString(evidence.reference()),
                      r.getId(),
                      operator,
                      evidence.snapshot(),
                      clock.instant());
                  o.resumeAfterRefund(clock.instant());
                  orders.save(o);
                  requests.save(r);
                  audit(r, before, "OPERATOR", operator, "REFUND_CLOSED");
                  workflow.changed(o, orderBefore, "OPERATOR", operator, "REFUND_CLOSED", null);
                  return OrderRefundView.from(r);
                },
                replay ->
                    OrderRefundView.from(
                        requests.findById(replay).orElseThrow(OrderWorkflowService::notFound))));
  }

  @Transactional(propagation = Propagation.NEVER)
  public RefundClosureStore.Confirmation confirmClosure(
      UUID buyer, UUID id, String key, long expected) {
    workflow.requireAccount(ActorKind.BUYER, buyer);
    var request =
        OrderWorkflowService.request(
            "BUYER", buyer, "CONFIRM_REFUND_CLOSURE", key, List.of(id, expected));
    var prior =
        transactions.execute(
            status ->
                keys.replayPermanent(
                    request,
                    existing ->
                        closureStore
                            .find(existing)
                            .filter(c -> c.subject().buyerId().equals(buyer))
                            .orElseThrow(OrderWorkflowService::notFound)));
    if (prior != null && prior.isPresent()) return prior.get();
    var initial =
        requests
            .findById(id)
            .filter(r -> r.getBuyerId().equals(buyer))
            .orElseThrow(OrderWorkflowService::notFound);
    UUID confirmationId = UUID.randomUUID();
    return transactions.execute(
        status ->
            keys.executePermanent(
                request,
                confirmationId,
                201,
                ignored -> {
                  var order = workflow.ownedLocked(ActorKind.BUYER, buyer, initial.getOrderId());
                  var r = requests.findById(id).orElseThrow(OrderWorkflowService::notFound);
                  r.requireVersion(expected);
                  if (r.getStatus() != OrderRefundRequest.Status.FAILED_REVIEW
                      || r.getReceivedAt() != null
                      || r.getRefundId() == null
                      || order.getStatus() != Order.Status.REFUND_PENDING) throw conflict();
                  var c =
                      new RefundClosureStore.Confirmation(
                          confirmationId,
                          subject(r, order),
                          clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                  closureStore.confirm(c);
                  return c;
                },
                existing ->
                    closureStore
                        .find(existing)
                        .filter(c -> c.subject().buyerId().equals(buyer))
                        .orElseThrow(OrderWorkflowService::notFound)));
  }

  private static RefundClosureEvidence.Subject subject(OrderRefundRequest r, Order o) {
    return new RefundClosureEvidence.Subject(
        r.getId(),
        r.getRefundId(),
        o.getPaymentId(),
        o.getId(),
        r.getBuyerId(),
        r.getVersion(),
        o.getVersion());
  }

  private void approved(Order order, OrderRefundRequest r) {
    workflow.emit(
        order,
        "RefundApproved",
        order.getPaymentId(),
        new OrderLifecycleEvents.RefundApproved(
            r.getId(),
            order.getId(),
            order.getPaymentId(),
            OrderWorkflowService.time(r.getAcceptedAt()),
            OrderWorkflowService.time(r.getDecisionAt()),
            order.getVersion()));
  }

  private void audit(
      OrderRefundRequest r,
      OrderRefundRequest.Status before,
      String actor,
      String ref,
      String reason) {
    audit.record(
        new AuditLog.Entry(
            actor,
            ref,
            AuditLog.Action.REFUND_REQUEST_TRANSITION,
            "ORDER_REFUND_REQUEST",
            r.getId(),
            before == null ? null : before.name(),
            r.getStatus().name(),
            reason,
            null,
            r.getOrderId().toString(),
            clock.instant()));
  }

  private static OrderStateException conflict() {
    return new OrderStateException(OrderStateException.Reason.STATE_CONFLICT);
  }
}
