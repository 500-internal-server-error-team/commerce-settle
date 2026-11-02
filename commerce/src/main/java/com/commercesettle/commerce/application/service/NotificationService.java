package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.application.port.NotificationStore.Draft;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.model.Notification.Kind;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.OrderRefundRepository;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);
  private final NotificationStore store;
  private final NotificationGateway gateway;
  private final AccountRepository accounts;
  private final IdempotentRequests keys;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final OrderRepository orders;
  private final OrderRefundRepository refunds;
  private final TransactionTemplate transactions;
  private final RetentionService retention;

  public NotificationService(
      NotificationStore store,
      NotificationGateway gateway,
      AccountRepository accounts,
      IdempotentRequests keys,
      ObjectMapper mapper,
      Clock clock,
      OrderRepository orders,
      OrderRefundRepository refunds,
      PlatformTransactionManager manager,
      RetentionService retention) {
    this.store = store;
    this.gateway = gateway;
    this.accounts = accounts;
    this.keys = keys;
    this.mapper = mapper;
    this.clock = clock;
    this.orders = orders;
    this.refunds = refunds;
    this.retention = retention;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public PageResult<Notification> own(ActorKind kind, UUID recipient, int page, int size) {
    requireAccount(kind, recipient);
    return store.page(kind, recipient, null, page, size);
  }

  @Transactional(readOnly = true)
  public Notification own(ActorKind kind, UUID recipient, UUID id) {
    requireAccount(kind, recipient);
    var notification = find(id);
    if (notification.recipientKind() != kind || !notification.recipientId().equals(recipient))
      throw OrderWorkflowService.notFound();
    return notification;
  }

  @Transactional(readOnly = true)
  public PageResult<Notification> deliveries(Notification.Status status, int page, int size) {
    return store.page(null, null, status, page, size);
  }

  @Transactional(readOnly = true)
  public Notification find(UUID id) {
    return store.find(id).orElseThrow(OrderWorkflowService::notFound);
  }

  @Transactional(readOnly = true)
  public PageResult<NotificationStore.Attempt> attempts(UUID id, int page, int size) {
    find(id);
    return store.attempts(id, page, size);
  }

  public NotificationGateway.Receipt receipt(UUID id) {
    if (store.find(id).filter(n -> n.channel() != Notification.Channel.MOCK_KAKAO).isPresent())
      throw OrderWorkflowService.notFound();
    var receipt = gateway.lookup(id, 0);
    if (receipt == null) throw OrderWorkflowService.notFound();
    return receipt;
  }

  @Transactional(propagation = Propagation.NEVER)
  public Notification replay(String operator, UUID id, String key, long expected, String reason) {
    var request =
        new IdempotentRequests.Request(
            "OPERATOR", operator, "REPLAY_NOTIFICATION", key, List.of(id, expected, reason));
    var prior = transactions.execute(status -> keys.replayPermanent(request, this::find));
    if (prior.isPresent()) return prior.get();
    try {
      var first = find(id);
      if (first.version() != expected)
        throw new com.commercesettle.commerce.domain.exception.VersionConflictException(
            "notification", expected, first.version());
      if (!first.replayable()) throw new Notification.StateConflictException();
      retention.prepareReplay(id);
    } catch (RuntimeException error) {
      var completed = transactions.execute(status -> keys.replayPermanent(request, this::find));
      if (completed.isPresent()) return completed.get();
      throw error;
    }
    return transactions.execute(
        status ->
            keys.executePermanent(
                request,
                id,
                202,
                ignored -> {
                  store.replay(id, expected, operator, reason, clock.instant());
                  var replayed = find(id);
                  afterCommit(
                      () ->
                          LOGGER
                              .atInfo()
                              .addKeyValue("event", "notification.replay")
                              .addKeyValue("deliveryId", id)
                              .addKeyValue("result", replayed.status().name())
                              .log("Notification replay committed"));
                  return replayed;
                },
                this::find));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void order(Order order, Kind kind, UUID eventId, Instant occurredAt) {
    var payload =
        mapper
            .createObjectNode()
            .put("orderId", order.getId().toString())
            .put("productId", order.getProductId().toString())
            .put("productName", order.getProductName())
            .put("totalAmount", order.getAmounts().totalAmount());
    var source = eventId == null ? UUID.randomUUID() : eventId;
    if (kind == Kind.SALE_COMPLETED)
      append(order, kind, order.getId(), ActorKind.SELLER, source, occurredAt, payload);
    else {
      append(order, kind, order.getId(), ActorKind.BUYER, source, occurredAt, payload);
      if (kind == Kind.CANCELLATION_COMPLETED)
        append(order, kind, order.getId(), ActorKind.SELLER, source, occurredAt, payload);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void refund(
      Order order, OrderRefundRequest request, Kind kind, UUID eventId, Instant occurredAt) {
    var payload =
        mapper
            .createObjectNode()
            .put("orderId", order.getId().toString())
            .put("productId", order.getProductId().toString())
            .put("productName", order.getProductName())
            .put("totalAmount", order.getAmounts().totalAmount())
            .put("requestId", request.getId().toString())
            .put("decision", request.getStatus().name());
    UUID business = kind == Kind.REFUND_ACTION_REQUIRED ? request.getRefundId() : request.getId();
    UUID source = eventId == null ? UUID.randomUUID() : eventId;
    append(order, kind, business, ActorKind.BUYER, source, occurredAt, payload);
    if (kind != Kind.RETURN_DECIDED && kind != Kind.RETURN_RECEIVED)
      append(order, kind, business, ActorKind.SELLER, source, occurredAt, payload);
  }

  @Transactional
  public void accept(EventEnvelope<JsonNode> event, NotificationStore.Source source) {
    var accepted = store.accept(event, source, clock.instant());
    if (accepted == NotificationStore.Acceptance.NEW)
      store.claimNotice(event.eventId(), clock.instant()).ifPresent(this::applyNotice);
    else if (accepted == NotificationStore.Acceptance.CONFLICT)
      quarantineLog("NOTIFICATION_EVENT_BODY_CONFLICT");
  }

  @Transactional
  public void quarantine(NotificationStore.Source source, String code) {
    store.quarantine(source, code);
    quarantineLog(code);
  }

  private static void quarantineLog(String code) {
    afterCommit(
        () ->
            LOGGER
                .atError()
                .addKeyValue("event", "notification.notice.quarantine")
                .addKeyValue("result", "REVIEW_REQUIRED")
                .addKeyValue("errorCode", code)
                .log("Notification notice quarantine committed"));
  }

  public boolean processNextNotice() {
    var selected = new java.util.concurrent.atomic.AtomicReference<NotificationStore.Notice>();
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                var notice = store.claimNotice(null, clock.instant());
                if (notice.isEmpty()) return false;
                selected.set(notice.get());
                applyNotice(notice.get());
                return true;
              }));
    } catch (RuntimeException e) {
      if (selected.get() == null) throw e;
      boolean conflict =
          e instanceof IllegalArgumentException
              || e instanceof Notification.ContentConflictException;
      String code = conflict ? "NOTIFICATION_NOTICE_CONFLICT" : "NOTIFICATION_NOTICE_APPLY_FAILED";
      transactions.executeWithoutResult(status -> retryNotice(selected.get(), code, conflict));
      return true;
    }
  }

  private void applyNotice(NotificationStore.Notice notice) {
    var event = notice.event();
    var p = event.payload();
    Kind kind = Kind.valueOf(p.required("kind").textValue());
    String source = event.eventType().equals("PaymentNoticeRequested") ? "payment" : "settlement";
    Instant occurredAt = event.occurredAt().toInstant();
    if (source.equals("payment")) {
      var found = orders.findById(uuid(p, "orderId"));
      if (found.isEmpty()) {
        waitNotice(notice);
        return;
      }
      var order = found.get();
      var amounts = order.getAmounts();
      if (!order.getBuyerId().equals(uuid(p, "buyerId"))
          || !order.getSellerId().equals(uuid(p, "sellerId"))
          || amounts.totalAmount() != p.required("totalAmount").longValue()
          || amounts.externalAmount() != p.required("externalAmount").longValue()
          || amounts.mileageAmount() != p.required("mileageAmount").longValue())
        throw new IllegalArgumentException("Notification order context mismatch");
      if (!uuid(p, "paymentCycleId").equals(order.getPaymentCycleId())
          || !uuid(p, "paymentId").equals(order.getPaymentId())) {
        waitNotice(notice);
        return;
      }
      if (kind == Kind.PAYMENT_COMPLETED) {
        if (!List.of(
                Order.Status.PAID,
                Order.Status.REFUND_PENDING,
                Order.Status.REFUNDED,
                Order.Status.PURCHASE_CONFIRMED)
            .contains(order.getStatus())) {
          waitNotice(notice);
          return;
        }
      } else {
        var refund = refunds.findByRefundId(uuid(p, "refundId"));
        if (refund.isEmpty()
            || !refund.get().getOrderId().equals(order.getId())
            || refund.get().getStatus() != OrderRefundRequest.Status.COMPLETED
            || order.getStatus() != Order.Status.REFUNDED) {
          waitNotice(notice);
          return;
        }
      }
      occurredAt =
          java.time.OffsetDateTime.parse(p.required("processedAt").textValue()).toInstant();
      var enriched = (com.fasterxml.jackson.databind.node.ObjectNode) p.deepCopy();
      enriched.put("productId", order.getProductId().toString());
      enriched.put("productName", order.getProductName());
      append(
          new Draft(
              source,
              event.eventId(),
              kind,
              uuid(p, "businessRef"),
              ActorKind.BUYER,
              uuid(p, "buyerId"),
              occurredAt,
              enriched));
      if (kind == Kind.REFUND_COMPLETED) {
        var seller = enriched.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) seller)
            .remove(List.of("mileageBalanceAfter", "walletVersion"));
        append(
            new Draft(
                source,
                event.eventId(),
                kind,
                uuid(p, "businessRef"),
                ActorKind.SELLER,
                uuid(p, "sellerId"),
                occurredAt,
                seller));
      }
    } else {
      if (kind == Kind.PAYOUT_COMPLETED)
        occurredAt =
            java.time.OffsetDateTime.parse(p.required("succeededAt").textValue()).toInstant();
      append(
          new Draft(
              source,
              event.eventId(),
              kind,
              uuid(p, "businessRef"),
              ActorKind.SELLER,
              uuid(p, "sellerId"),
              occurredAt,
              p));
    }
    if (store.completeNotice(notice.id(), notice.version(), clock.instant()))
      noticeLog(notice, "APPLIED", null);
  }

  private void waitNotice(NotificationStore.Notice notice) {
    retryNotice(notice, "NOTIFICATION_ORDER_PREDECESSOR_NOT_READY", false);
  }

  private void retryNotice(NotificationStore.Notice notice, String code, boolean review) {
    String result = store.retryNotice(notice.id(), notice.version(), code, review, clock.instant());
    if (result != null) noticeLog(notice, result, code);
  }

  private static void noticeLog(NotificationStore.Notice notice, String result, String code) {
    afterCommit(
        () -> {
          Level level =
              result.equals("REVIEW_REQUIRED")
                  ? Level.ERROR
                  : "NOTIFICATION_ORDER_PREDECESSOR_NOT_READY".equals(code)
                      ? Level.DEBUG
                      : result.equals("WAITING") ? Level.WARN : Level.INFO;
          var entry =
              LOGGER
                  .atLevel(level)
                  .addKeyValue("event", "notification.notice")
                  .addKeyValue("eventId", notice.event().eventId())
                  .addKeyValue("result", result);
          if (code != null) entry.addKeyValue("errorCode", code);
          entry.log("Notification notice state committed");
        });
  }

  private static void afterCommit(Runnable log) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            log.run();
          }
        });
  }

  private static UUID uuid(JsonNode node, String field) {
    return UUID.fromString(node.required(field).textValue());
  }

  private void append(
      Order order,
      Kind kind,
      UUID business,
      ActorKind recipient,
      UUID eventId,
      Instant at,
      com.fasterxml.jackson.databind.JsonNode payload) {
    append(
        new Draft(
            "commerce",
            eventId,
            kind,
            business,
            recipient,
            recipient == ActorKind.BUYER ? order.getBuyerId() : order.getSellerId(),
            at,
            payload));
  }

  private void append(Draft draft) {
    store.append(
        new Draft(
            draft.source(),
            draft.eventId(),
            draft.kind(),
            draft.businessRef(),
            draft.recipientKind(),
            draft.recipientId(),
            draft.occurredAt(),
            draft.payload(),
            gateway.destination(draft.recipientKind(), draft.recipientId())));
  }

  private void requireAccount(ActorKind kind, UUID id) {
    if (accounts
        .findById(kind, id)
        .filter(com.commercesettle.commerce.domain.model.Account::canLogIn)
        .isEmpty()) throw new AuthenticationFailedException();
  }
}
