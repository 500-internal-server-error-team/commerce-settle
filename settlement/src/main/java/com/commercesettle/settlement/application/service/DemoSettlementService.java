package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.exception.DemoRunException;
import com.commercesettle.settlement.application.port.AuditLog;
import com.commercesettle.settlement.application.port.DemoBatchLauncher;
import com.commercesettle.settlement.application.port.DemoRunSource;
import com.commercesettle.settlement.application.port.DemoSettlementStore;
import com.commercesettle.settlement.application.port.DemoSettlementStore.Run;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DemoSettlementService {
  private final DemoSettlementStore store;
  private final DemoRunSource source;
  private final DemoBatchLauncher launcher;
  private final IdempotentRequests keys;
  private final AuditLog audit;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final boolean enabled;
  private final boolean isolated;
  private final boolean scheduled;
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          Thread.ofPlatform().name("demo-settlement-", 0).factory());

  public DemoSettlementService(
      DemoSettlementStore store,
      DemoRunSource source,
      DemoBatchLauncher launcher,
      IdempotentRequests keys,
      AuditLog audit,
      Clock clock,
      PlatformTransactionManager manager,
      @Value("${app.demo.enabled:false}") boolean enabled,
      @Value("${app.demo.isolated:false}") boolean isolated,
      @Value("${settlement.daily-batch.schedule-enabled:true}") boolean scheduled) {
    this.store = store;
    this.source = source;
    this.launcher = launcher;
    this.keys = keys;
    this.audit = audit;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
    this.enabled = enabled;
    this.isolated = isolated;
    this.scheduled = scheduled;
  }

  @PostConstruct
  void initialize() {
    if (enabled && (!isolated || scheduled))
      throw new IllegalStateException("Demo mode requires isolation and disabled daily schedule");
    store.requireEnvironment(enabled);
  }

  public boolean enabled() {
    return enabled;
  }

  public Run start(String operator, String key, UUID id) {
    requireEnabled();
    var existing = store.find(id);
    DemoRunSource.Source snapshot = existing.isEmpty() ? source.read(id) : null;
    Run run =
        transactions.execute(
            status ->
                keys.execute(
                    new IdempotentRequests.Request(
                        "OPERATOR", operator, "DEMO_SETTLEMENT_START", key, List.of(id)),
                    202,
                    () -> store.find(id).orElseGet(() -> prepare(operator, id, snapshot)),
                    Run::id,
                    stored -> store.find(stored).orElseThrow()));
    if (active.add(id)) {
      try {
        executor.execute(
            () -> {
              try {
                store.status(id, launcher.runDemo(run));
              } catch (RuntimeException e) {
                store.status(id, "FAILED");
                org.slf4j.LoggerFactory.getLogger(DemoSettlementService.class)
                    .atWarn()
                    .addKeyValue("runId", id)
                    .addKeyValue("event", "settlement.demo-batch")
                    .log("Demo batch failed; retry the same run");
              } finally {
                active.remove(id);
              }
            });
      } catch (java.util.concurrent.RejectedExecutionException e) {
        active.remove(id);
        throw new DemoRunException("DEMO_NOT_READY", "다른 시연 배치가 실행 중입니다. 같은 회차로 다시 요청하세요.");
      }
    }
    return run;
  }

  private Run prepare(String operator, UUID id, DemoRunSource.Source snapshot) {
    if (snapshot == null
        || !id.equals(snapshot.id())
        || snapshot.frozenAt() == null
        || snapshot.pendingCount() != 0
        || snapshot.orderIds().size() > 10000)
      throw new DemoRunException("DEMO_NOT_READY", "회차 고정·구매확정·환불 완료를 확인하세요.");
    var targets = Set.copyOf(snapshot.payableOrderIds());
    var refunds = Set.copyOf(snapshot.refundedOrderIds());
    if (targets.isEmpty()) throw new DemoRunException("DEMO_NO_PAYABLE_ITEMS", "지급 가능한 항목이 없습니다.");
    if (!Set.copyOf(snapshot.orderIds()).containsAll(targets)
        || !Set.copyOf(snapshot.orderIds()).containsAll(refunds)
        || targets.stream().anyMatch(refunds::contains))
      throw new DemoRunException("DEMO_SCOPE_CONFLICT", "회차의 지급·환불 대상이 일치하지 않습니다.");
    var cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var items = store.items(targets);
    var refunded = store.items(refunds);
    if (items.size() != targets.size()
        || refunded.size() != refunds.size()
        || !items.stream()
            .map(DemoSettlementStore.Item::orderId)
            .collect(Collectors.toSet())
            .equals(targets)
        || !refunded.stream()
            .map(DemoSettlementStore.Item::orderId)
            .collect(Collectors.toSet())
            .equals(refunds)
        || items.stream()
            .anyMatch(
                i ->
                    !snapshot.sellerId().equals(i.sellerId())
                        || !i.status().equals("PAYABLE")
                        || i.payoutId() != null
                        || i.readyAt() == null
                        || !i.readyAt().isBefore(cutoff))
        || refunded.stream()
            .anyMatch(
                i -> !snapshot.sellerId().equals(i.sellerId()) || !i.status().equals("REFUNDED")))
      throw new DemoRunException("DEMO_NOT_READY", "Settlement의 결제·구매확정·환불 반영을 기다려 주세요.");
    var run =
        store.create(
            new Run(
                id,
                snapshot.sellerId(),
                cutoff,
                items.stream()
                    .map(DemoSettlementStore.Item::id)
                    .collect(Collectors.toUnmodifiableSet()),
                refunds.size(),
                snapshot.deliverySeconds(),
                snapshot.refundSeconds(),
                "READY"));
    audit.record(
        new AuditLog.Entry(
            "OPERATOR",
            operator,
            AuditLog.Action.DEMO_RUN_CREATED,
            "DEMO_RUN",
            id,
            null,
            "READY",
            "DEMO_CONTROL",
            id.toString(),
            clock.instant()));
    return run;
  }

  public Run get(UUID id) {
    requireEnabled();
    return store
        .find(id)
        .orElseThrow(() -> new DemoRunException("DEMO_NOT_FOUND", "정산 시연 회차를 찾을 수 없습니다."));
  }

  public List<DemoSettlementStore.Batch> batches(UUID id) {
    get(id);
    return store.batches(id);
  }

  private void requireEnabled() {
    if (!enabled) throw new DemoRunException("DEMO_DISABLED", "서버의 시연 모드가 꺼져 있습니다.");
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }
}
