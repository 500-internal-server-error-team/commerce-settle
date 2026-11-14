package com.commercesettle.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.exception.DemoRunException;
import com.commercesettle.settlement.application.port.DemoRunSource;
import com.commercesettle.settlement.application.port.DemoSettlementStore;
import com.commercesettle.settlement.application.service.DemoSettlementService;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "springdoc.paths-to-match=/internal/demo-settlement-runs/**",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "app.demo.enabled=true",
      "app.demo.isolated=true",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({
  DailySettlementJobIntegrationTest.Contexts.class,
  DemoSettlementIntegrationTest.Time.class
})
class DemoSettlementIntegrationTest {
  static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired DemoSettlementService runs;
  @Autowired DemoSettlementStore store;
  @Autowired DailySettlementLauncher launcher;
  @Autowired SettlementItemRepository items;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired com.commercesettle.settlement.application.service.SettlementEventService events;
  @Autowired com.commercesettle.settlement.application.service.RefundEventService refunds;
  @Autowired DailySettlementLock lock;
  @Autowired org.springframework.batch.core.repository.JobRepository jobs;
  @Autowired DailySettlementJobIntegrationTest.FakeContextReader contexts;
  @MockitoBean DemoRunSource source;
  @Autowired org.springframework.test.web.servlet.MockMvc http;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

  @Test
  @DisplayName("실제 시연 구매확정 bytes를 디코딩해 짧은 기한 원장에 한 번만 반영한다")
  void acceptsDemoPurchaseThroughListenerAndDatabase() throws Exception {
    var item =
        items.save(
            SettlementItem.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new SettlementAmounts(1000, 0, 1000, 1000, 0, 150, 850),
                0,
                NOW.minusSeconds(60)));
    var payload =
        new com.commercesettle.settlement.application.event.PurchaseConfirmedPayload(
            item.getPaymentId(),
            item.getOrderId(),
            4,
            NOW.atOffset(ZoneOffset.UTC),
            NOW.minusSeconds(11).atOffset(ZoneOffset.UTC),
            NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),
            UUID.randomUUID(),
            10);
    var event =
        new com.commercesettle.settlement.application.event.EventEnvelope<>(
            UUID.randomUUID(),
            "PurchaseConfirmed",
            1,
            NOW.atOffset(ZoneOffset.UTC),
            item.getOrderId(),
            4,
            payload);
    var body = mapper.writeValueAsBytes(event);
    com.commercesettle.settlement.support.EventContract.assertValid(body);
    var listener =
        new com.commercesettle.settlement.infrastructure.messaging.SettlementEventListener(
            mapper, events, refunds, null, "settlement.v1");
    var record =
        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
            "commerce-settle.commerce.v1", 0, 0, item.getPaymentId().toString(), body);
    var acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
    listener.receive(record, () -> acknowledged.set(true));
    listener.receive(record, () -> acknowledged.set(true));
    assertThat(acknowledged).isTrue();
    assertThat(status(item)).isEqualTo("PAYABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.inbox WHERE event_id=? AND status='APPLIED'",
                Integer.class,
                event.eventId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("시연 내부 응답 DTO의 런타임 필드·필수값·nullable·목록 참조가 정식 명세와 일치한다")
  void preservesRuntimeDemoSchemas() throws Exception {
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/v3/api-docs"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    var actual = mapper.readTree(response.getContentAsString()).path("components").path("schemas");
    var expected =
        mapper
            .readTree(getClass().getResourceAsStream("/contracts/openapi.json"))
            .path("components")
            .path("schemas");
    for (String name :
        List.of(
            "DemoCapabilities",
            "DemoSettlementStart",
            "DemoSettlementBatch",
            "DemoSettlementRun")) {
      var generated = actual.path(name);
      var contract = expected.path(name);
      assertThat(generated.isMissingNode()).as(name).isFalse();
      assertThat(generated.get("additionalProperties"))
          .isEqualTo(contract.get("additionalProperties"));
      var generatedRequired = new java.util.HashSet<String>();
      generated.path("required").forEach(v -> generatedRequired.add(v.asText()));
      var expectedRequired = new java.util.HashSet<String>();
      contract.path("required").forEach(v -> expectedRequired.add(v.asText()));
      assertThat(generatedRequired).as(name + " required").isEqualTo(expectedRequired);
      assertThat(
              generated.path("properties").properties().stream()
                  .map(java.util.Map.Entry::getKey)
                  .toList())
          .containsExactlyInAnyOrderElementsOf(
              contract.path("properties").properties().stream()
                  .map(java.util.Map.Entry::getKey)
                  .toList());
      for (var property : contract.path("properties").properties()) {
        var declared = generated.path("properties").path(property.getKey());
        var expectedTypes = schemaTypes(property.getValue());
        var actualTypes = schemaTypes(declared);
        assertThat(actualTypes)
            .as(name + "." + property.getKey() + " type")
            .isEqualTo(expectedTypes);
        if (property.getValue().has("items"))
          assertThat(declared.get("items")).isEqualTo(property.getValue().get("items"));
      }
    }
  }

  @Test
  @DisplayName("같은 판매자의 같은 날 두 회차를 분리하고 밖의 항목·중복 요청을 지급에 섞지 않는다")
  void separatesRunsAndReplaysWithoutExtendingMembership() {
    UUID seller = UUID.randomUUID();
    var first = payable(seller, NOW.minusNanos(1000));
    var outside = payable(seller, NOW.minusNanos(1000));
    UUID one = UUID.randomUUID();
    when(source.read(one)).thenReturn(snapshot(one, seller, first, 5, 10));
    var created = runs.start("test", "demo-first-key", one);
    completed(one);
    assertThat(status(first)).isEqualTo("PAYOUT_RESERVED");
    assertThat(status(outside)).isEqualTo("PAYABLE");
    assertThat(runs.start("test", "demo-first-key", one)).isEqualTo(store.find(one).orElseThrow());
    completed(one);
    store.status(one, "STARTED");
    store.status(one, "FAILED");
    assertThat(runs.get(one).batchStatus()).isEqualTo("COMPLETED");
    assertThat(store.batches(one)).hasSize(1);
    assertThat(store.find(one).orElseThrow().cutoffAt()).isEqualTo(created.cutoffAt());
    UUID two = UUID.randomUUID();
    when(source.read(two)).thenReturn(snapshot(two, seller, outside, 2, 3));
    runs.start("test", "demo-second-key", two);
    completed(two);
    assertThat(store.batches(two)).hasSize(1);
    assertThat(store.batches(two).getFirst().payoutId())
        .isNotEqualTo(store.batches(one).getFirst().payoutId());
    assertThat(store.find(one).orElseThrow().deliverySeconds()).isEqualTo(5);
    assertThat(store.find(two).orElseThrow().deliverySeconds()).isEqualTo(2);
    assertThatThrownBy(() -> store.requireEnvironment(false))
        .hasMessageContaining("A demo database cannot start");
    assertThatThrownBy(() -> launcher.run(LocalDate.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("판매자 처리 실패는 건너뛴 완료로 숨기지 않고 같은 JobInstance로 재시작한다")
  void restartsFailedRunAndDoesNotDuplicatePayout() {
    UUID seller = UUID.randomUUID(), id = UUID.randomUUID();
    var item = payable(seller, NOW.minusNanos(1000));
    when(source.read(id)).thenReturn(snapshot(id, seller, item, 5, 10));
    contexts.failAlways.add(seller);
    runs.start("test", "demo-restart-key", id);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(runs.get(id).batchStatus()).isEqualTo("FAILED"));
    assertThat(store.batches(id)).isEmpty();
    var frozen = runs.get(id);
    contexts.failAlways.remove(seller);
    runs.start("test", "demo-restart-key", id);
    completed(id);
    assertThat(runs.get(id).cutoffAt()).isEqualTo(frozen.cutoffAt());
    assertThat(launcher.runDemo(runs.get(id))).isEqualTo("COMPLETED");
    assertThat(store.batches(id)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.batch_job_instance i JOIN settlement.batch_job_execution_params p ON p.parameter_value=? JOIN settlement.batch_job_execution e ON e.job_execution_id=p.job_execution_id AND e.job_instance_id=i.job_instance_id WHERE p.parameter_name='demoRunId'",
                Long.class,
                id.toString()))
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("다른 실행자 잠금 중에는 시작하지 않고 강제 종료 잔류는 정체 확인 후 같은 회차로 복구한다")
  void recoversOrphanOnlyAfterExecutorLockAndStalenessCheck() throws Exception {
    UUID id = UUID.randomUUID(), seller = UUID.randomUUID();
    var item = payable(seller, NOW.minusNanos(1000));
    var run =
        store.create(
            new DemoSettlementStore.Run(id, seller, NOW, Set.of(item.getId()), 0, 5, 10, "READY"));
    try (var held = lock.tryAcquire(id).orElseThrow()) {
      assertThat(launcher.runDemo(run)).isEqualTo("STARTED");
      assertThat(store.batches(id)).isEmpty();
    }
    var parameters =
        new org.springframework.batch.core.JobParametersBuilder()
            .addLocalDate("batchDate", NOW.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate())
            .addString("demoRunId", id.toString())
            .addString("demoCutoffAt", NOW.toString())
            .addString(DailySettlementLauncher.EXECUTOR_ID, "lost-executor", false)
            .toJobParameters();
    var orphan = jobs.createJobExecution(DailySettlementJobConfiguration.JOB_NAME, parameters);
    orphan.setStatus(org.springframework.batch.core.BatchStatus.STARTED);
    jobs.update(orphan);
    jdbc.update(
        "UPDATE settlement.batch_job_execution SET create_time=localtimestamp-interval '11 minutes',start_time=localtimestamp-interval '11 minutes',last_updated=localtimestamp-interval '11 minutes' WHERE job_execution_id=?",
        orphan.getId());
    assertThat(launcher.runDemo(run)).isEqualTo("COMPLETED");
    assertThat(store.batches(id)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.batch_job_execution WHERE job_execution_id=?",
                String.class,
                orphan.getId()))
        .isEqualTo("FAILED");
    assertThat(launcher.runDemo(run)).isEqualTo("COMPLETED");
    assertThat(store.batches(id)).hasSize(1);
  }

  @Test
  @DisplayName("마감 정각·구매확정 미반영 항목은 회차를 저장하거나 예약하지 않는다")
  void rejectsUnreadyAndFutureItemsBeforeFreezingCutoff() {
    UUID seller = UUID.randomUUID(), id = UUID.randomUUID();
    var item = payable(seller, NOW);
    when(source.read(id)).thenReturn(snapshot(id, seller, item, 1, 3600));
    assertThatThrownBy(() -> runs.start("test", "demo-unready-key", id))
        .isInstanceOf(DemoRunException.class);
    assertThat(store.find(id)).isEmpty();
    assertThat(status(item)).isEqualTo("PAYABLE");
  }

  @Test
  @DisplayName("회차 상태 행 잠금은 상한 안에 실패하고 해제 뒤 같은 회차를 다시 갱신한다")
  void boundsRunStatusLockAndRecoversAfterRelease() throws Exception {
    UUID id = new UUID(0, 1001);
    store.create(
        new DemoSettlementStore.Run(
            id, new UUID(0, 2001), NOW, Set.of(new UUID(0, 3001)), 0, 5, 10, "READY"));
    try (var blocker = jdbc.getDataSource().getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement("SELECT id FROM settlement.demo_runs WHERE id=? FOR UPDATE")) {
        statement.setObject(1, id);
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
      assertBoundedWrite(blocker, () -> store.status(id, "FAILED"));
    }
    assertThat(store.find(id).orElseThrow().batchStatus()).isEqualTo("READY");
    store.status(id, "FAILED");
    assertThat(store.find(id).orElseThrow().batchStatus()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("같은 회차의 미커밋 생성과 경합해도 잠금 상한 안에 실패하고 다시 생성한다")
  void boundsRunCreationConflictAndRecoversAfterRelease() throws Exception {
    UUID id = new UUID(0, 1003), seller = new UUID(0, 2003), item = new UUID(0, 3003);
    var run = new DemoSettlementStore.Run(id, seller, NOW, Set.of(item), 0, 5, 10, "READY");
    try (var blocker = jdbc.getDataSource().getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "INSERT INTO settlement.demo_runs(id,seller_id,cutoff_at,item_ids,refunded_count,delivery_seconds,refund_seconds,batch_status) VALUES(?,?,?,?::jsonb,0,5,10,'READY')")) {
        statement.setObject(1, id);
        statement.setObject(2, seller);
        statement.setObject(3, NOW.atOffset(ZoneOffset.UTC));
        statement.setString(4, "[\"" + item + "\"]");
        statement.executeUpdate();
      }
      assertBoundedWrite(blocker, () -> store.create(run));
    }
    assertThat(store.find(id)).isEmpty();
    assertThat(store.create(run).batchStatus()).isEqualTo("READY");
  }

  private void assertBoundedWrite(java.sql.Connection blocker, Runnable write) throws Exception {
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var update = workers.submit(write);
      try {
        assertThatThrownBy(() -> update.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
      } finally {
        blocker.rollback();
        try {
          update.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
          /* lock timeout */
        }
      }
    }
  }

  @Test
  @DisplayName("호출자가 정한 더 짧은 잠금 상한과 기존 트랜잭션 롤백을 유지한다")
  void preservesShorterStatusBudgetAndCallerRollback() {
    UUID id = new UUID(0, 1002);
    store.create(
        new DemoSettlementStore.Run(
            id, new UUID(0, 2002), NOW, Set.of(new UUID(0, 3002)), 0, 2, 3, "READY"));
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          jdbc.execute("SET LOCAL lock_timeout='250ms'");
          store.status(id, "FAILED");
          assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("250ms");
          assertThat(store.find(id).orElseThrow().batchStatus()).isEqualTo("FAILED");
          status.setRollbackOnly();
        });
    assertThat(store.find(id).orElseThrow().batchStatus()).isEqualTo("READY");
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Time {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock demoClock() {
      return java.time.Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  private Set<String> schemaTypes(com.fasterxml.jackson.databind.JsonNode value) {
    var result = new java.util.HashSet<String>();
    var type = value.path("type");
    if (type.isArray()) type.forEach(v -> result.add(v.asText()));
    else if (type.isTextual()) result.add(type.asText());
    if (value.path("nullable").asBoolean(false)) result.add("null");
    for (String union : List.of("anyOf", "oneOf", "allOf"))
      value.path(union).forEach(v -> result.addAll(schemaTypes(v)));
    return result;
  }

  private void completed(UUID id) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(runs.get(id).batchStatus()).isEqualTo("COMPLETED"));
  }

  private DemoRunSource.Source snapshot(
      UUID id, UUID seller, SettlementItem item, int delivery, int refund) {
    return new DemoRunSource.Source(
        id,
        seller,
        item.getProductId(),
        null,
        delivery,
        refund,
        Instant.now().atOffset(ZoneOffset.UTC),
        List.of(item.getOrderId()),
        List.of(item.getOrderId()),
        List.of(),
        0,
        0);
  }

  private SettlementItem payable(UUID seller, Instant ready) {
    var item =
        SettlementItem.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            seller,
            new SettlementAmounts(1000, 0, 1000, 1000, 0, 150, 850),
            0,
            ready.minusSeconds(600));
    item.markPayable(ready, ready.minusSeconds(300), ready);
    return items.save(item);
  }

  private String status(SettlementItem item) {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.settlement_items WHERE id=?", String.class, item.getId());
  }
}
