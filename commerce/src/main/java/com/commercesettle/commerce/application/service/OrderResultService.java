package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderLifecycleEvents;
import com.commercesettle.commerce.application.port.AuditLog;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox;
import com.commercesettle.commerce.domain.exception.InvalidReservationTransitionException;
import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import com.commercesettle.commerce.domain.repository.OrderRefundRepository;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.observability.AsyncObservation;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderResultService {
  private AsyncObservation observations = AsyncObservation.NOOP;
  private final PaymentAcceptanceInbox inbox;
  private final OrderRepository orders;
  private final OrderRefundRepository refunds;
  private final InventoryReservationService inventory;
  private final OrderBenefits benefits;
  private final OrderWorkflowService workflow;
  private final Clock clock;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final NotificationService notifications;

  public OrderResultService(
      PaymentAcceptanceInbox inbox,
      OrderRepository orders,
      OrderRefundRepository refunds,
      InventoryReservationService inventory,
      OrderBenefits benefits,
      OrderWorkflowService workflow,
      Clock clock,
      AuditLog audit,
      PlatformTransactionManager manager,
      NotificationService notifications) {
    this.inbox = inbox;
    this.orders = orders;
    this.refunds = refunds;
    this.inventory = inventory;
    this.benefits = benefits;
    this.workflow = workflow;
    this.clock = clock;
    this.audit = audit;
    this.notifications = notifications;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  @Transactional
  public void accept(EventEnvelope<JsonNode> event, PaymentAcceptanceInbox.Source source) {
    inbox.saveResult(event, source, clock.instant());
  }

  public boolean processNext() {
    return processEvent(null);
  }

  public boolean processEvent(UUID eventId) {
    var selected = new AtomicReference<UUID>();
    var scope = new AtomicReference<AsyncObservation.Scope>(AsyncObservation.Scope.NOOP);
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                var found = inbox.claimResult(eventId, clock.instant());
                if (found.isEmpty()) return false;
                var row = found.get();
                selected.set(row.id());
                scope.set(
                    observations.start(
                        "commerce.inbox.result",
                        row.traceParent(),
                        Map.of(
                            "event.id",
                            row.event().eventId().toString(),
                            "inbox.id",
                            row.id().toString()),
                        row.timing()));
                var p = row.event().payload();
                var order = orders.findLockedById(id(p, "orderId"));
                if (order.isEmpty()) {
                  inbox.retry(row.id(), "ORDER_NOT_FOUND", clock.instant());
                  return true;
                }
                try {
                  if (!apply(order.get(), row.event())) {
                    inbox.retry(row.id(), "ORDER_PREDECESSOR_NOT_READY", clock.instant());
                    return true;
                  }
                } catch (OrderStateException
                    | IllegalArgumentException
                    | InvalidReservationTransitionException e) {
                  // 도메인 검사는 부작용 전에 완료한다. 실패한 변경은 트랜잭션 전체를 롤백한다.
                  // 예약의 금지된 전이는 다시 실행해도 같은 결과이므로 재시도 없이 수동 확인으로 넘긴다.
                  throw new ResultConflictException();
                }
                inbox.applied(row.id(), order.get().getId(), clock.instant());
                return true;
              }));
    } catch (RuntimeException e) {
      scope.get().error(e);
      if (selected.get() == null) throw e;
      transactions.executeWithoutResult(
          status -> {
            if (e instanceof ResultConflictException)
              inbox.review(selected.get(), "ORDER_RESULT_CONFLICT", clock.instant());
            else inbox.retry(selected.get(), "ORDER_RESULT_APPLY_FAILED", clock.instant());
          });
      return true;
    } finally {
      scope.get().close();
    }
  }

  private boolean apply(Order o, EventEnvelope<JsonNode> event) {
    var p = event.payload();
    var payment = id(p, "paymentId");
    var now = clock.instant();
    if (event.eventType().startsWith("Payment")) {
      var cycle = id(p, "paymentCycleId");
      if (!cycle.equals(o.getPaymentCycleId())) return true;
      if (o.getPaymentId() != null && !o.getPaymentId().equals(payment)) throw conflict();
      if (event.eventType().equals("PaymentCancellationRejected")) {
        // 승인·미확정 거절은 예약 해제 근거가 아니다. 최종 결제 확정 또는 승인 차단 취소를 기다린다.
        return true;
      }
      var result =
          switch (event.eventType()) {
            case "PaymentConfirmed" -> Order.Status.PAID;
            case "PaymentDeclined" -> Order.Status.DECLINED;
            case "PaymentCancelled" -> Order.Status.CANCELLED;
            default -> throw new IllegalArgumentException("Unsupported payment result");
          };
      if (result == Order.Status.PAID) validateAmounts(o, p);
      var before = o.getStatus();
      if (o.applyPayment(
          payment,
          cycle,
          result,
          result == Order.Status.PAID
              ? OffsetDateTime.parse(p.required("confirmedAt").textValue()).toInstant()
              : null,
          now)) {
        if (result == Order.Status.PAID) {
          if (o.getEventId() == null) inventory.consume(o.getId(), cycle);
          benefits.consume(o, now);
        } else {
          if (o.getEventId() == null) inventory.release(o.getId(), cycle);
          benefits.release(o, now);
        }
        orders.save(o);
        workflow.changed(o, before, "SERVICE", "payment", event.eventType(), event.eventId());
        if (result == Order.Status.PAID)
          notifications.order(
              o,
              com.commercesettle.commerce.domain.model.Notification.Kind.SALE_COMPLETED,
              event.eventId(),
              o.getPaymentConfirmedAt());
        if (result == Order.Status.CANCELLED)
          notifications.order(
              o,
              com.commercesettle.commerce.domain.model.Notification.Kind.CANCELLATION_COMPLETED,
              event.eventId(),
              event.occurredAt().toInstant());
      }
      return true;
    }
    if (o.getPaymentId() == null) return false;
    if (!o.getPaymentId().equals(payment)) throw conflict();
    var refundId = id(p, "refundId");
    if (event.eventType().equals("RefundAccepted")) {
      var r = refunds.findById(id(p, "requestId"));
      if (r.isEmpty()) return false;
      if (!r.get().getOrderId().equals(o.getId())) throw conflict();
      var before = r.get().getStatus();
      if (r.get().linkRefund(refundId, now)) {
        refunds.save(r.get());
        auditRefund(r.get(), before, event);
      }
      return true;
    }
    var found = refunds.findByRefundId(refundId);
    if (found.isEmpty()) return false;
    var r = found.get();
    if (!r.getOrderId().equals(o.getId())) throw conflict();
    if (event.eventType().equals("RefundFailed")) {
      if (!p.required("reservationReleased").isBoolean()
          || !p.required("reservationReleased").booleanValue()) throw conflict();
      var before = r.getStatus();
      if (r.fail(now)) {
        refunds.save(r);
        auditRefund(r, before, event);
        notifications.refund(
            o,
            r,
            com.commercesettle.commerce.domain.model.Notification.Kind.REFUND_ACTION_REQUIRED,
            event.eventId(),
            event.occurredAt().toInstant());
      }
      return true;
    }
    if (!event.eventType().equals("RefundCompleted")) throw conflict();
    var refundBefore = r.getStatus();
    if (r.complete(now)) {
      var before = o.getStatus();
      if (o.getFulfillmentStatus() == Order.FulfillmentStatus.UNSHIPPED && o.getEventId() == null)
        inventory.restoreForRefund(o.getId(), refundId);
      benefits.restore(o, refundId, now);
      o.restoreRefund(now);
      orders.save(o);
      refunds.save(r);
      auditRefund(r, refundBefore, event);
      workflow.changed(o, before, "SERVICE", "payment", "REFUND_RESTORED", event.eventId());
      workflow.emit(
          o,
          "RefundOrderRestored",
          payment,
          new OrderLifecycleEvents.RefundOrderRestored(
              refundId, payment, o.getId(), o.getVersion()));
    }
    return true;
  }

  private void auditRefund(
      OrderRefundRequest r, OrderRefundRequest.Status before, EventEnvelope<JsonNode> event) {
    audit.record(
        new AuditLog.Entry(
            "SERVICE",
            "payment",
            AuditLog.Action.REFUND_REQUEST_TRANSITION,
            "ORDER_REFUND_REQUEST",
            r.getId(),
            before.name(),
            r.getStatus().name(),
            event.eventType(),
            event.eventId(),
            r.getOrderId().toString(),
            clock.instant()));
  }

  private static void validateAmounts(Order o, JsonNode p) {
    var a = o.getAmounts();
    if (!id(p, "buyerId").equals(o.getBuyerId())
        || !id(p, "sellerId").equals(o.getSellerId())
        || !id(p, "productId").equals(o.getProductId())
        || !p.required("policyVersion").asText().equals(o.getPolicyVersion())
        || p.required("orderVersion").longValue() > o.getVersion()
        || p.required("grossAmount").longValue() != a.grossAmount()
        || p.required("couponAmount").longValue() != a.couponAmount()
        || p.required("totalAmount").longValue() != a.totalAmount()
        || p.required("mileageAmount").longValue() != a.mileageAmount()
        || p.required("externalAmount").longValue() != a.externalAmount()
        || p.required("sellerCouponAmount").longValue() != a.sellerCouponAmount()
        || p.required("platformCouponAmount").longValue() != a.platformCouponAmount()
        || p.required("settlementBaseAmount").longValue() != a.settlementBaseAmount()
        || p.required("feeAmount").longValue() != a.feeAmount()
        || p.required("sellerNetAmount").longValue() != a.sellerNetAmount()) throw conflict();
  }

  private static UUID id(JsonNode p, String field) {
    return UUID.fromString(p.required(field).textValue());
  }

  private static OrderStateException conflict() {
    return new OrderStateException(OrderStateException.Reason.STATE_CONFLICT);
  }

  private static class ResultConflictException extends RuntimeException {}
}
