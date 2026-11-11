package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source;
import com.commercesettle.commerce.domain.model.Order.PaymentLinkResult;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.observability.AsyncObservation;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 영속 인계와 주문 연결을 분리한다. 반영 실패 뒤 재시도 기록은 별도 트랜잭션이다. */
@Service
public class PaymentAcceptedService {
  private static final Logger LOG = LoggerFactory.getLogger(PaymentAcceptedService.class);
  private AsyncObservation observations = AsyncObservation.NOOP;
  private final PaymentAcceptanceInbox inbox;
  private final OrderRepository orders;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public PaymentAcceptedService(
      PaymentAcceptanceInbox inbox,
      OrderRepository orders,
      Clock clock,
      PlatformTransactionManager manager) {
    this.inbox = inbox;
    this.orders = orders;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  @Transactional
  public void accept(EventEnvelope<PaymentAcceptedPayload> event, Source source) {
    inbox.saveWaiting(event, source, clock.instant());
  }

  public boolean processNext() {
    return process(null);
  }

  /** 격리 시험·지정 작업의 재개가 다른 대기 건을 소비하지 않게 한다. */
  public boolean processEvent(UUID eventId) {
    return process(java.util.Objects.requireNonNull(eventId));
  }

  private boolean process(UUID eventId) {
    var selected = new AtomicReference<UUID>();
    var scope = new AtomicReference<AsyncObservation.Scope>(AsyncObservation.Scope.NOOP);
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                var work = inbox.claim(eventId, clock.instant());
                if (work.isEmpty()) return false;
                var row = work.get();
                selected.set(row.id());
                scope.set(
                    observations.start(
                        "commerce.inbox.accepted",
                        row.traceParent(),
                        Map.of(
                            "event.id",
                            row.event().eventId().toString(),
                            "inbox.id",
                            row.id().toString()),
                        row.timing()));
                var payload = row.event().payload();
                var found = orders.findLockedById(payload.orderId());
                if (found.isEmpty()) {
                  inbox.retry(row.id(), "ORDER_NOT_FOUND", clock.instant());
                  return true;
                }
                var order = found.get();
                var mapped = orders.findByPaymentId(payload.paymentId());
                if (mapped.isPresent() && !mapped.get().getId().equals(order.getId())) {
                  inbox.review(row.id(), "ORDER_PAYMENT_ID_CONFLICT", clock.instant());
                  return true;
                }
                var result =
                    order.linkPayment(
                        payload.paymentId(),
                        payload.paymentCycleId(),
                        payload.orderVersion(),
                        clock.instant());
                if (result == PaymentLinkResult.LINKED) orders.save(order);
                if (result == PaymentLinkResult.LINKED
                    || result == PaymentLinkResult.ALREADY_LINKED) {
                  inbox.applied(row.id(), order.getId(), clock.instant());
                } else {
                  inbox.review(row.id(), "ORDER_" + result.name(), clock.instant());
                }
                return true;
              }));
    } catch (RuntimeException exception) {
      scope.get().error(exception);
      if (selected.get() == null) throw exception;
      transactions.executeWithoutResult(
          status -> inbox.retry(selected.get(), "PAYMENT_LINK_APPLY_FAILED", clock.instant()));
      LOG.warn(
          "Payment link deferred: inboxId={}, errorCode=PAYMENT_LINK_APPLY_FAILED", selected.get());
      return true;
    } finally {
      scope.get().close();
    }
  }
}
