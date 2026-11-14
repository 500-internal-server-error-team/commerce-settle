package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.DemoRunView;
import com.commercesettle.commerce.application.exception.DemoRunException;
import com.commercesettle.commerce.application.port.DemoRunStore;
import com.commercesettle.commerce.application.port.DemoRunStore.Run;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRunService {
  private final DemoRunStore store;
  private final IdempotentRequests keys;
  private final ProductRepository products;
  private final OrderWorkflowService workflow;
  private final Clock clock;
  private final boolean enabled;
  private final boolean isolated;
  private final boolean autoShipment;
  private final com.commercesettle.commerce.application.port.AuditLog audit;

  public DemoRunService(
      DemoRunStore store,
      IdempotentRequests keys,
      ProductRepository products,
      OrderWorkflowService workflow,
      Clock clock,
      com.commercesettle.commerce.application.port.AuditLog audit,
      @Value("${app.demo.enabled:false}") boolean enabled,
      @Value("${app.demo.isolated:false}") boolean isolated,
      @Value("${app.order-workflow.auto-shipment-enabled:false}") boolean autoShipment) {
    this.store = store;
    this.keys = keys;
    this.products = products;
    this.workflow = workflow;
    this.clock = clock;
    this.enabled = enabled;
    this.isolated = isolated;
    this.autoShipment = autoShipment;
    this.audit = audit;
  }

  @PostConstruct
  void initialize() {
    if (enabled && (!isolated || autoShipment))
      throw new IllegalStateException("Demo mode requires isolation and manual shipment");
    store.requireEnvironment(enabled);
  }

  public boolean enabled() {
    return enabled;
  }

  @Transactional
  public DemoRunView create(
      String operator,
      String key,
      UUID seller,
      UUID product,
      UUID event,
      int delivery,
      int refund) {
    requireEnabled();
    if (delivery < 1 || delivery > 3600 || refund < 1 || refund > 3600)
      throw rejected("DEMO_OPTIONS_INVALID", "배송·환불 기간은 각각 1~3600초입니다.");
    var p =
        products.findById(product).orElseThrow(() -> rejected("DEMO_NOT_FOUND", "상품을 찾을 수 없습니다."));
    if (!p.getSellerId().equals(seller))
      throw rejected("DEMO_SCOPE_CONFLICT", "상품과 판매자가 일치하지 않습니다.");
    if (event != null && !store.eventMatches(event, product))
      throw rejected("DEMO_SCOPE_CONFLICT", "이벤트와 상품이 일치하지 않습니다.");
    UUID id = UUID.randomUUID();
    return keys.executePermanent(
        new IdempotentRequests.Request(
            "OPERATOR",
            operator,
            "DEMO_RUN_CREATE",
            key,
            Arrays.asList(seller, product, event, delivery, refund)),
        id,
        201,
        ignored -> {
          store.create(
              new Run(
                  id,
                  seller,
                  product,
                  event,
                  delivery,
                  refund,
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  null,
                  List.of()));
          audit(operator, id, true);
          return view(id);
        },
        this::view);
  }

  public Run lockForNewOrder(UUID runId) {
    if (runId == null) return null;
    requireEnabled();
    Run run = require(runId, true);
    if (run.frozenAt() != null) throw rejected("DEMO_RUN_FROZEN", "고정된 회차에는 새 주문을 넣을 수 없습니다.");
    if (store.atCapacity(runId)) throw rejected("DEMO_LIMIT_EXCEEDED", "회차당 주문은 10000건까지 지원합니다.");
    return run;
  }

  public void apply(Order order, Run run) {
    if (run == null) return;
    if (!run.productId().equals(order.getProductId())
        || !run.sellerId().equals(order.getSellerId())
        || !Objects.equals(run.eventId(), order.getEventId()))
      throw rejected("DEMO_SCOPE_CONFLICT", "회차의 상품·판매자·이벤트를 확인하세요.");
    order.configureDemo(run.id(), run.deliverySeconds(), run.refundSeconds());
  }

  @Transactional(readOnly = true)
  public DemoRunView view(UUID id) {
    requireEnabled();
    Run run = require(id, false);
    var members = store.members(id);
    if (members.size() > 10000) throw rejected("DEMO_LIMIT_EXCEEDED", "회차당 주문은 10000건까지 지원합니다.");
    return DemoRunView.from(run, members);
  }

  @Transactional
  public DemoRunView freeze(String operator, String key, UUID id) {
    requireEnabled();
    return keys.executePermanent(
        new IdempotentRequests.Request("OPERATOR", operator, "DEMO_RUN_FREEZE", key, List.of(id)),
        id,
        200,
        ignored -> freezeNew(operator, id),
        this::view);
  }

  private DemoRunView freezeNew(String operator, UUID id) {
    Run run = require(id, true);
    if (run.frozenAt() == null) {
      var members = store.members(id);
      if (members.isEmpty() || members.size() > 10000)
        throw rejected("DEMO_SCOPE_CONFLICT", "1~10000건의 주문이 필요합니다.");
      if (run.eventId() != null && !store.eventSoldOut(run.eventId()))
        throw rejected("DEMO_NOT_READY", "이벤트 매진을 확인한 뒤 회차를 고정하세요.");
      if (members.stream()
          .anyMatch(
              m ->
                  List.of("PAYMENT_PENDING", "CANCEL_PENDING", "REFUND_PENDING")
                      .contains(m.status())))
        throw rejected("DEMO_NOT_READY", "결제·취소·환불 처리가 끝난 뒤 회차를 고정하세요.");
      store.freeze(id, members.stream().map(DemoRunStore.Member::id).toList(), clock.instant());
      audit(operator, id, false);
    }
    return view(id);
  }

  public com.commercesettle.commerce.application.dto.DemoStageView stage(
      String operator, String key, UUID id, boolean shipment, UUID after) {
    requireEnabled();
    Run run = require(id, false);
    if (run.frozenAt() == null) throw rejected("DEMO_NOT_READY", "먼저 회차의 주문을 고정하세요.");
    int start = after == null ? 0 : run.orderIds().indexOf(after) + 1;
    if (after != null && start == 0) throw rejected("DEMO_SCOPE_CONFLICT", "회차의 진행 위치를 확인하세요.");
    int end = Math.min(start + 50, run.orderIds().size()), waiting = 0;
    for (var orderId : run.orderIds().subList(start, end)) {
      try {
        workflow.demoStep(operator, key + ":" + orderId, id, orderId, shipment);
      } catch (DemoRunException e) {
        if (!e.code().equals("DEMO_NOT_READY")) throw e;
        waiting++;
      }
    }
    return new com.commercesettle.commerce.application.dto.DemoStageView(
        end < run.orderIds().size() ? run.orderIds().get(end - 1) : null,
        end - start - waiting,
        waiting);
  }

  private Run require(UUID id, boolean lock) {
    return store.find(id, lock).orElseThrow(() -> rejected("DEMO_NOT_FOUND", "시연 회차를 찾을 수 없습니다."));
  }

  private void audit(String operator, UUID id, boolean created) {
    audit.record(
        new com.commercesettle.commerce.application.port.AuditLog.Entry(
            "OPERATOR",
            operator,
            created
                ? com.commercesettle.commerce.application.port.AuditLog.Action.DEMO_RUN_CREATED
                : com.commercesettle.commerce.application.port.AuditLog.Action.DEMO_RUN_FROZEN,
            "DEMO_RUN",
            id,
            created ? null : "OPEN",
            created ? "OPEN" : "FROZEN",
            "DEMO_CONTROL",
            null,
            id.toString(),
            clock.instant()));
  }

  private void requireEnabled() {
    if (!enabled) throw rejected("DEMO_DISABLED", "서버의 시연 모드가 꺼져 있습니다.");
  }

  private static DemoRunException rejected(String code, String message) {
    return new DemoRunException(code, message);
  }
}
