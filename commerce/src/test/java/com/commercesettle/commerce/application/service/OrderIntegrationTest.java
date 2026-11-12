package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.holdTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.support.JdbcExecutionRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=true",
      "management.zipkin.tracing.export.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.coupons.expiry-scheduler-enabled=false",
      "app.coupons.grade-scheduler-enabled=false",
      "app.sale-events.scheduler-enabled=false",
      "management.tracing.sampling.probability=1.0",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=regular-order-api-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=regular-order-api-test-delivery-key-32bytes",
      "app.address.encryption-key=regular-order-api-test-address-key-32bytes",
      "app.internal-auth.senders.gateway.current=" + OrderIntegrationTest.GATEWAY_CREDENTIAL
    })
@AutoConfigureObservability(metrics = false)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OrderIntegrationTest.SqlRecording.class)
class OrderIntegrationTest {
  @MockitoBean OrderAdmissionService admission;
  @MockitoBean com.commercesettle.commerce.infrastructure.cache.RedisRunIdAuthGate authGate;
  static final String GATEWAY_CREDENTIAL = "test-gateway-credential-0123456789abcdef0123";
  private static final ShippingAddress SHIPPING =
      new ShippingAddress("시험 수령인", "000-0000-0000", "00001", "가상시 시험로 1", "101호");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
          .withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService catalog;
  @Autowired OrderRepository orders;
  @Autowired AddressCipher cipher;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcExecutionRecorder sql;

  @TestConfiguration(proxyBeanMethods = false)
  static class SqlRecording {
    @Bean
    static JdbcExecutionRecorder jdbcExecutionRecorder() {
      return new JdbcExecutionRecorder();
    }
  }

  @Test
  @DisplayName("신규 일반 주문은 재고를 한 번 잠그고 멱등 재요청은 다시 예약하지 않는다")
  void locksInventoryOncePerNewOrder() {
    var f = fixture(2);
    String idempotencyKey = key();

    var created = sql.measure(() -> post(f.buyer(), idempotencyKey, body(f)));
    var replay = sql.measure(() -> post(f.buyer(), idempotencyKey, body(f)));
    var conflict = sql.measure(() -> post(f.buyer(), idempotencyKey, body(f).put("quantity", 2)));

    System.out.printf(
        "ORDER_QUERY_COUNTS new=%d inventoryLocks=%d timeoutSettings=%d replay=%d conflict=%d%n",
        created.executions().size(),
        created.inventoryLocks(),
        created.timeoutSettings(),
        replay.executions().size(),
        conflict.executions().size());
    assertThat(created.result().getStatusCode().value()).isEqualTo(201);
    assertThat(replay.result().getBody()).isEqualTo(created.result().getBody());
    problem(conflict.result(), 409, "COMMON_IDEMPOTENCY_CONFLICT");
    assertThat(created.inventoryLocks()).isEqualTo(1);
    assertThat(created.timeoutSettings()).isLessThanOrEqualTo(5);
    assertThat(created.executions()).hasSizeLessThanOrEqualTo(21);
    assertThat(replay.executions()).hasSizeLessThanOrEqualTo(4);
    assertThat(conflict.executions()).hasSizeLessThanOrEqualTo(3);
    assertThat(replay.inventoryLocks()).isZero();
    assertThat(conflict.inventoryLocks()).isZero();
    assertInventory(f.product(), 1, 1);
    assertThat(count("inventory_reservations", "product_id", f.product())).isEqualTo(1);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "ORDER_QUERY_BENCHMARK", matches = "true")
  @DisplayName("동일한 일반 주문 입력으로 SQL·연결 대기·HTTP 지연을 계측한다")
  void measuresOrderQueryCost() throws Exception {
    jdbc.execute("create extension if not exists pg_stat_statements");
    var distributed = new java.util.ArrayList<Fixture>();
    for (int i = 0; i < 20; i++) distributed.add(queryFixture(i, 100));
    var shared = queryFixture(20, 1000);
    var contended =
        distributed.stream()
            .map(f -> new Fixture(f.buyer(), f.address(), shared.seller(), shared.product()))
            .toList();
    runOrders(distributed, 100, 4, "warm-distributed");
    runOrders(contended, 100, 4, "warm-shared");
    for (var distribution :
        List.of(Map.entry("distributed", distributed), Map.entry("one-product", contended))) {
      jdbc.execute("select pg_stat_statements_reset()");
      var waits = new java.util.concurrent.atomic.AtomicInteger();
      var polls = new java.util.concurrent.atomic.AtomicInteger();
      var sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
      try (var observer =
              java.sql.DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = observer.createStatement();
          var sampler = Executors.newSingleThreadExecutor()) {
        var observation =
            sampler.submit(
                () -> {
                  while (sampling.get()) {
                    try (var rows =
                        statement.executeQuery(
                            "select count(*) from pg_stat_activity where datname=current_database()"
                                + " and wait_event_type='Lock'")) {
                      rows.next();
                      waits.addAndGet(rows.getInt(1));
                      polls.incrementAndGet();
                    } catch (java.sql.SQLException error) {
                      throw new IllegalStateException(error);
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
                  }
                });
        JdbcExecutionRecorder.Sample<List<Long>> sample;
        try {
          sample =
              sql.measure(() -> runOrders(distribution.getValue(), 400, 4, distribution.getKey()));
        } finally {
          sampling.set(false);
        }
        observation.get(5, TimeUnit.SECONDS);
        var database =
            jdbc.queryForMap(
                "select sum(calls) as calls,sum(total_exec_time) as execution_ms from pg_stat_statements"
                    + " where dbid=(select oid from pg_database where datname=current_database())"
                    + " and query not like '%pg_stat_%' and query not in ('BEGIN','COMMIT')");
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("distribution", distribution.getKey());
        result.put("requests", sample.result().size());
        result.put("clients", 4);
        result.put("wallMs", sample.nanos() / 1e6);
        result.put("httpMs", percentiles(sample.result()));
        result.put("acquireMs", percentiles(sample.acquisitions()));
        result.put("jdbcExecutions", sample.executions().size());
        result.put(
            "jdbcMs",
            sample.executions().stream().mapToLong(JdbcExecutionRecorder.Execution::nanos).sum()
                / 1e6);
        result.put("inventoryLocks", sample.inventoryLocks());
        result.put("timeoutSettings", sample.timeoutSettings());
        result.put("database", database);
        result.put("lockWaitBackendSamples", waits.get());
        result.put("lockPolls", polls.get());
        result.put("postgresImage", POSTGRES.getContainerInfo().getImageId());
        System.out.println("ORDER_QUERY_BENCHMARK " + mapper.writeValueAsString(result));
      }
    }
    for (var f : distributed) assertInventory(f.product(), 75, 25);
    assertInventory(shared.product(), 500, 500);
  }

  private Fixture queryFixture(int index, int stock) {
    UUID buyer = queryId("buyer", index);
    UUID address = queryId("address", index);
    UUID seller = queryId("seller", index);
    UUID product = queryId("product", index);
    var sealed = cipher.seal(address, SHIPPING);
    jdbc.update(
        "insert into commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','구매자','ACTIVE',now(),now(),0)",
        buyer,
        buyer + "@example.test");
    jdbc.update(
        "insert into commerce.buyer_addresses (id,buyer_id,recipient_ciphertext,phone_ciphertext,address_ciphertext,is_default,created_at,updated_at) values (?,?,?,?,?,true,now(),now())",
        address,
        buyer,
        sealed.recipient(),
        sealed.phone(),
        sealed.address());
    jdbc.update(
        "insert into commerce.products (id,seller_id,name,unit_price,status,created_at,updated_at) values (?,?,'쿼리 비교 상품',10000,'ON_SALE',now(),now())",
        product,
        seller);
    jdbc.update(
        "insert into commerce.product_inventory (id,product_id,available_qty,reserved_qty,event_allocated_qty,sold_qty,created_at,updated_at) values (?,?,?,0,0,0,now(),now())",
        queryId("inventory", index),
        product,
        stock);
    return new Fixture(buyer, address, seller, product);
  }

  private static UUID queryId(String kind, int index) {
    return UUID.nameUUIDFromBytes(
        ("order-query:" + kind + ":" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private List<Long> runOrders(List<Fixture> fixtures, int requests, int clients, String phase) {
    try (var pool = Executors.newFixedThreadPool(clients)) {
      var ready = new CountDownLatch(clients);
      var start = new CountDownLatch(1);
      var futures = new java.util.ArrayList<java.util.concurrent.Future<List<Long>>>();
      for (int lane = 0; lane < clients; lane++) {
        int offset = lane;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("start timed out");
                  var times = new java.util.ArrayList<Long>();
                  for (int i = offset; i < requests; i += clients) {
                    var f = fixtures.get(i % fixtures.size());
                    long began = System.nanoTime();
                    assertThat(
                            post(f.buyer(), "query-" + phase + "-" + i, body(f))
                                .getStatusCode()
                                .value())
                        .isEqualTo(201);
                    times.add(System.nanoTime() - began);
                  }
                  return times;
                }));
      }
      if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("clients not ready");
      start.countDown();
      var times = new java.util.ArrayList<Long>();
      for (var future : futures) times.addAll(future.get(90, TimeUnit.SECONDS));
      return times;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<String, Double> percentiles(List<Long> nanos) {
    var sorted = nanos.stream().sorted().toList();
    return Map.of(
        "p50", sorted.get((int) Math.ceil(sorted.size() * .50) - 1) / 1e6,
        "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1) / 1e6,
        "p99", sorted.get((int) Math.ceil(sorted.size() * .99) - 1) / 1e6);
  }

  private JsonNode canonicalApi;
  private JsonNode generatedApi;
  private SchemaRegistry canonicalSchemas;
  private SchemaRegistry schemas;

  @Test
  @DisplayName("접수 중단 중에도 기존 멱등 결과와 본문 충돌을 우선하고 거절 키는 재사용한다")
  void preservesIdempotencyWhileAdmissionIsClosed() {
    var f = fixture(2);
    String accepted = key();
    var first = post(f.buyer(), accepted, body(f));
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    UUID order = UUID.fromString(first.getBody().path("id").asText());
    org.mockito.Mockito.doThrow(
            new com.commercesettle.commerce.application.exception.OrderCapacityExceededException())
        .when(admission)
        .requireAdmission();
    org.mockito.Mockito.clearInvocations(admission);

    var replay = post(f.buyer(), accepted, body(f));
    assertThat(replay.getStatusCode().value()).isEqualTo(201);
    assertThat(replay.getBody().path("id").asText()).isEqualTo(order.toString());
    problem(
        post(f.buyer(), accepted, body(f).put("quantity", 2)), 409, "COMMON_IDEMPOTENCY_CONFLICT");
    org.mockito.Mockito.verifyNoInteractions(admission);
    String rejected = key();
    var unavailable = post(f.buyer(), rejected, body(f));
    problem(unavailable, 503, "COMMON_CAPACITY_EXCEEDED");
    assertThat(unavailable.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    assertInventory(f.product(), 1, 1);
    assertThat(count("orders", "buyer_id", f.buyer())).isEqualTo(1);
    assertThat(count("outbox", "aggregate_id", order)).isEqualTo(1);

    org.mockito.Mockito.doNothing().when(admission).requireAdmission();
    assertThat(post(f.buyer(), rejected, body(f)).getStatusCode().value()).isEqualTo(201);
    assertInventory(f.product(), 0, 2);
  }

  @Test
  @DisplayName("재고 행 잠금 대기 상한은 주문 멱등 기록까지 롤백하고 잠금 해제 뒤 같은 키로 접수한다")
  void boundsInventoryLockWaitAndRollsBackIdempotency() throws Exception {
    var f = fixture(2);
    String key = key();
    long addressCount =
        jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class);
    long outboxCount = jdbc.queryForObject("select count(*) from commerce.outbox", Long.class);
    long movements = count("inventory_movements", "product_id", f.product());
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "select id from commerce.product_inventory where product_id=? for update")) {
        statement.setObject(1, f.product());
        statement.executeQuery().close();
      }
      try {
        Instant started = Instant.now();
        problem(post(f.buyer(), key, body(f)), 503, "COMMON_TEMPORARILY_UNAVAILABLE");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(6));
        assertThat(count("orders", "buyer_id", f.buyer())).isZero();
        assertThat(count("inventory_reservations", "product_id", f.product())).isZero();
        assertThat(count("inventory_movements", "product_id", f.product())).isEqualTo(movements);
        assertThat(jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class))
            .isEqualTo(addressCount);
        assertThat(jdbc.queryForObject("select count(*) from commerce.outbox", Long.class))
            .isEqualTo(outboxCount);
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from commerce.idempotency_records where actor_id=? and operation='ORDER_CREATE'",
                    Long.class,
                    f.buyer().toString()))
            .isZero();
        assertInventory(f.product(), 2, 0);
      } finally {
        holder.rollback();
      }
    }
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("상품 변경의 재고 잠금이 풀리면 새 가격·버전을 검사하고 이전 견적은 거절한다")
  void readsLatestProductAfterSellerChange() throws Exception {
    var f = fixture(2);
    String requestKey = key();
    var tx = new TransactionTemplate(transactions);
    try (var held =
        holdTransaction(
            tx,
            () ->
                catalog.change(
                    f.seller(), f.product(), key(), "변경 상품", 12000, ProductStatus.ON_SALE, 0))) {
      problem(post(f.buyer(), requestKey, body(f)), 503, "COMMON_TEMPORARILY_UNAVAILABLE");
    }

    problem(post(f.buyer(), requestKey, body(f)), 409, "COMMON_VERSION_CONFLICT");
    assertInventory(f.product(), 2, 0);
    assertThat(count("orders", "buyer_id", f.buyer())).isZero();
    var accepted =
        post(
            f.buyer(),
            requestKey,
            body(f).put("expectedProductVersion", 1).put("expectedTotalAmount", 12000));
    assertThat(accepted.getStatusCode().value()).isEqualTo(201);
    assertThat(accepted.getBody().path("grossAmount").asLong()).isEqualTo(12000);
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("주소 변경의 구매자 행 잠금이 풀린 뒤 새 배송지를 읽어 주문 사본을 만든다")
  void serializesAddressSnapshotWithBookChange() throws Exception {
    var f = fixture(2);
    var updated = new ShippingAddress("변경 수령인", "000-1111-2222", "00002", "가상시 새주소 2", "");
    CountDownLatch changed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var change =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            addresses.change(f.buyer(), f.address(), key(), updated, true, 0);
                            changed.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("test release timed out");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                            return true;
                          }));
      assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
      var create = pool.submit(() -> post(f.buyer(), key(), body(f)));
      try {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> create.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      assertThat(change.get(10, TimeUnit.SECONDS)).isTrue();
      var result = create.get(10, TimeUnit.SECONDS);
      assertThat(result.getStatusCode().value()).isEqualTo(201);
      UUID orderId = UUID.fromString(result.getBody().path("id").asText());
      var snapshot = orders.findAddressByBuyerAndOrderId(f.buyer(), orderId).orElseThrow();
      assertThat(cipher.open(snapshot.getId(), snapshot.getSealed())).isEqualTo(updated);
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @CsvSource({"0,10000,CREDIT_CARD,test-card", "1000,9000,EASY_PAY,test-pay", "10000,0,NULL,NULL"})
  @DisplayName("실제 HTTP 주문·주소·재고·멱등 기록·Outbox를 한 번 저장하고 금액과 명세를 보존한다")
  void createsOrderWithAtomicPaymentRequest(long mileage, long external, String method, String ref)
      throws Exception {
    var fixture = fixture(3);
    var body = body(fixture).put("mileageAmount", mileage);
    if (external == 0) {
      body.putNull("externalMethod").putNull("methodAccountRef");
    } else {
      body.put("externalMethod", method).put("methodAccountRef", ref);
    }
    String key = key();
    var created = post(fixture.buyer(), key, body);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    var order = created.getBody();
    assertThat(order.path("status").asText()).isEqualTo("PAYMENT_PENDING");
    assertThat(order.path("externalAmount").asLong()).isEqualTo(external);
    assertThat(order.path("createdAt").asText()).endsWith("+09:00");
    assertThat(created.getHeaders().getLocation().toString())
        .isEqualTo("/api/orders/" + order.path("id").asText());
    matches("Order", order);
    matches("OrderCreate", body);
    UUID orderId = UUID.fromString(order.path("id").asText());
    var stored = orders.findByBuyerAndId(fixture.buyer(), orderId).orElseThrow();
    var snapshot = orders.findAddressByBuyerAndOrderId(fixture.buyer(), orderId).orElseThrow();
    assertThat(snapshot.getId()).isNotEqualTo(fixture.address());
    assertThat(cipher.open(snapshot.getId(), snapshot.getSealed())).isEqualTo(SHIPPING);
    assertInventory(fixture.product(), 2, 1);
    var event =
        mapper.readTree(
            jdbc.queryForObject(
                "select raw_body from commerce.outbox where aggregate_id=?",
                byte[].class,
                orderId));
    matches("EventEnvelope", event);
    matches("OrderPaymentRequestedPayload", event.path("payload"));
    assertThat(event.path("eventType").asText()).isEqualTo("OrderPaymentRequested");
    assertThat(event.path("aggregateId").asText()).isEqualTo(orderId.toString());
    assertThat(event.path("aggregateVersion").asLong()).isEqualTo(stored.getVersion());
    assertThat(event.path("payload").path("orderVersion").asLong()).isEqualTo(stored.getVersion());
    assertThat(event.path("payload").path("paymentCycleId").asText())
        .isEqualTo(stored.getPaymentCycleId().toString());
    assertThat(event.path("payload").path("mileageAmount").asLong()).isEqualTo(mileage);
    assertThat(event.path("payload").path("externalAmount").asLong()).isEqualTo(external);
    assertThat(Instant.parse(event.path("payload").path("paymentWaitUntil").asText()))
        .isEqualTo(stored.getCreatedAt().plusSeconds(600));
    assertThat(
            jdbc.queryForMap(
                "select topic,message_key from commerce.outbox where aggregate_id=?", orderId))
        .containsEntry("topic", "commerce-settle.commerce.v1")
        .containsEntry("message_key", orderId.toString());
    assertThat(
            jdbc.queryForMap(
                "select expires_at from commerce.idempotency_records where resource_id=?", orderId))
        .containsEntry("expires_at", null);
    // 최초 성공 뒤 주소와 상품이 사라져도 같은 요청은 기존 주문으로 연결한다.
    addresses.delete(fixture.buyer(), fixture.address(), key(), 0);
    catalog.delete(fixture.seller(), fixture.product(), key(), 0);
    assertThat(post(fixture.buyer(), key, body).getBody()).isEqualTo(order);
    assertThat(get(fixture.buyer(), "/api/orders/" + orderId).getBody()).isEqualTo(order);
    assertThat(cipher.open(snapshot.getId(), snapshot.getSealed())).isEqualTo(SHIPPING);
    problem(
        post(fixture.buyer(), key, body.deepCopy().put("quantity", 2)),
        409,
        "COMMON_IDEMPOTENCY_CONFLICT");
    if (external == 0) {
      problem(
          post(fixture.buyer(), key, body.deepCopy().put("methodAccountRef", "null")),
          409,
          "COMMON_IDEMPOTENCY_CONFLICT");
    }
    assertThat(count("orders", "id", orderId)).isEqualTo(1);
    assertThat(count("inventory_reservations", "order_id", orderId)).isEqualTo(1);
    assertThat(count("outbox", "aggregate_id", orderId)).isEqualTo(1);
    assertInventory(fixture.product(), 2, 1);
  }

  @Test
  @DisplayName("Outbox의 실제 DB 저장 실패는 주문·주소·재고·멱등 기록을 롤백하고 같은 키로 재시도된다")
  void rollsBackEveryWriteIfOutboxFails() {
    var f = fixture(2);
    String key = key();
    long addressCount =
        jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class);
    long outboxCount = jdbc.queryForObject("select count(*) from commerce.outbox", Long.class);
    long movements = count("inventory_movements", "product_id", f.product());
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_order_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected storage failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_order_outbox BEFORE INSERT ON commerce.outbox FOR EACH ROW EXECUTE FUNCTION commerce.reject_order_outbox()");
    try {
      assertThat(post(f.buyer(), key, body(f)).getStatusCode().is5xxServerError()).isTrue();
      assertThat(count("orders", "buyer_id", f.buyer())).isZero();
      assertThat(count("inventory_reservations", "product_id", f.product())).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from commerce.idempotency_records where actor_id=? and operation='ORDER_CREATE'",
                  Long.class,
                  f.buyer().toString()))
          .isZero();
      assertInventory(f.product(), 2, 0);
      assertThat(jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class))
          .isEqualTo(addressCount);
      assertThat(jdbc.queryForObject("select count(*) from commerce.outbox", Long.class))
          .isEqualTo(outboxCount);
      assertThat(count("inventory_movements", "product_id", f.product())).isEqualTo(movements);
    } finally {
      jdbc.execute("DROP TRIGGER reject_order_outbox ON commerce.outbox");
      jdbc.execute("DROP FUNCTION commerce.reject_order_outbox()");
    }
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("서로 다른 구매자가 마지막 재고를 동시에 주문해도 한 건만 접수한다")
  void concurrentOrdersCannotOversell() throws Exception {
    var first = fixture(1);
    UUID other = buyer();
    UUID address = addresses.register(other, key(), SHIPPING, true).id();
    var second = new Fixture(other, address, first.seller(), first.product());
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(first, second).stream()
              .map(
                  f ->
                      pool.submit(
                          () -> {
                            start.await();
                            return post(f.buyer(), key(), body(f));
                          }))
              .toList();
      start.countDown();
      var responses =
          List.of(
              futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
      assertThat(responses)
          .extracting(r -> r.getStatusCode().value())
          .containsExactlyInAnyOrder(201, 409);
      assertThat(
              responses.stream()
                  .filter(r -> r.getStatusCode().value() == 409)
                  .findFirst()
                  .orElseThrow()
                  .getBody()
                  .path("code")
                  .asText())
          .isEqualTo("INVENTORY_INSUFFICIENT");
    }
    assertInventory(first.product(), 0, 1);
    assertThat(count("orders", "product_id", first.product())).isEqualTo(1);
    assertThat(count("inventory_reservations", "product_id", first.product())).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키의 동시 생성은 같은 주문으로 모이고 예약·이벤트를 중복 저장하지 않는다")
  void concurrentSameKeyCreatesOnce() throws Exception {
    var f = fixture(3);
    String key = key();
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return post(f.buyer(), key, body(f));
                          }))
              .toList();
      start.countDown();
      for (var future : futures) {
        assertThat(future.get(15, TimeUnit.SECONDS).getStatusCode().value()).isIn(201, 409);
      }
    }
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    assertThat(count("orders", "buyer_id", f.buyer())).isEqualTo(1);
    assertInventory(f.product(), 2, 1);
  }

  @Test
  @DisplayName("타인 조회는 404이고 목록은 본인 자료만 같은 시각의 ID 역순으로 페이지한다")
  void scopesAndPaginatesDbReads() throws Exception {
    var f = fixture(4);
    var ids =
        java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> post(f.buyer(), key(), body(f)).getBody().path("id").asText())
            .sorted(Comparator.reverseOrder())
            .toList();
    jdbc.update(
        "update commerce.orders set created_at='2026-01-01T00:00:00Z' where buyer_id=?", f.buyer());
    var page = get(f.buyer(), "/api/orders?size=2").getBody();
    matches("OrderPage", page);
    assertThat(page.path("totalElements").asInt()).isEqualTo(3);
    assertThat(page.path("totalPages").asInt()).isEqualTo(2);
    assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(ids.get(0));
    assertThat(page.path("items").get(1).path("id").asText()).isEqualTo(ids.get(1));
    assertThat(
            get(f.buyer(), "/api/orders?page=1&size=2")
                .getBody()
                .path("items")
                .get(0)
                .path("id")
                .asText())
        .isEqualTo(ids.get(2));
    UUID other = buyer();
    problem(get(other, "/api/orders/" + ids.get(0)), 404, "COMMON_RESOURCE_NOT_FOUND");
    assertThat(get(other, "/api/orders").getBody().path("totalElements").asInt()).isZero();
    problem(get(f.buyer(), "/api/orders?size=101"), 400, "COMMON_VALIDATION_FAILED");
    problem(get(f.buyer(), "/api/orders?page=-1"), 400, "COMMON_VALIDATION_FAILED");
  }

  @ParameterizedTest
  @CsvSource({"21474837,100,1", "2147483647,100,1", "2147483647,1,2"})
  @DisplayName("큰 페이지도 빈 목록과 본인 주문 수를 반환한다")
  void returnsEmptyOwnedPageAcrossJpaOffsetBoundary(int page, int size, int totalPages) {
    var f = fixture(2);
    for (int i = 0; i < 2; i++) {
      assertThat(post(f.buyer(), key(), body(f)).getStatusCode().value()).isEqualTo(201);
    }
    var other = fixture(1);
    assertThat(post(other.buyer(), key(), body(other)).getStatusCode().value()).isEqualTo(201);

    var response = get(f.buyer(), "/api/orders?page=" + page + "&size=" + size);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    var result = response.getBody();
    assertThat(result.path("items").isArray()).isTrue();
    assertThat(result.path("items")).isEmpty();
    assertThat(result.path("page").asInt()).isEqualTo(page);
    assertThat(result.path("size").asInt()).isEqualTo(size);
    assertThat(result.path("totalElements").asLong()).isEqualTo(2);
    assertThat(result.path("totalPages").asInt()).isEqualTo(totalPages);
    assertThat(
            get(other.buyer(), "/api/orders?page=" + page + "&size=" + size)
                .getBody()
                .path("totalElements")
                .asLong())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("가격·버전 변경과 판매 중단·타인/삭제 주소를 거절하고 예약을 남기지 않는다")
  void rejectsInvalidReferencesAndPrices() {
    var f = fixture(3);
    problem(
        post(f.buyer(), key(), body(f).put("expectedProductVersion", 1)),
        409,
        "COMMON_VERSION_CONFLICT");
    problem(
        post(f.buyer(), key(), body(f).put("expectedTotalAmount", 9999)),
        409,
        "COMMON_VERSION_CONFLICT");
    var other = fixture(1);
    problem(
        post(f.buyer(), key(), body(f).put("addressId", other.address().toString())),
        404,
        "COMMON_RESOURCE_NOT_FOUND");
    addresses.delete(f.buyer(), f.address(), key(), 0);
    problem(post(f.buyer(), key(), body(f)), 404, "COMMON_RESOURCE_NOT_FOUND");
    catalog.change(
        other.seller(), other.product(), key(), "중단 상품", 10000, ProductStatus.STOPPED, 0);
    problem(
        post(other.buyer(), key(), body(other).put("expectedProductVersion", 1)),
        404,
        "COMMON_RESOURCE_NOT_FOUND");
    assertInventory(f.product(), 3, 0);
    assertInventory(other.product(), 1, 0);
    assertThat(count("orders", "buyer_id", f.buyer())).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "productId",
        "quantity",
        "eventId",
        "buyerCouponId",
        "mileageAmount",
        "addressId",
        "expectedProductVersion",
        "expectedEventVersion",
        "expectedTotalAmount",
        "externalMethod",
        "methodAccountRef"
      })
  @DisplayName("nullable 필드를 포함한 정식 입력 11개 키는 모두 필수다")
  void requiresEveryKey(String field) {
    var f = fixture(1);
    var body = body(f);
    body.remove(field);
    problem(post(f.buyer(), key(), body), 400, "COMMON_VALIDATION_FAILED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "eventId",
        "buyerCouponId",
        "expectedEventVersion",
        "mileageAmount",
        "externalMethod",
        "methodAccountRef",
        "quantity",
        "unknown"
      })
  @DisplayName("미지원 기능·잘못된 금액/수단·추가 필드를 입력 오류로 거절한다")
  void rejectsUnsupportedAndInvalidInput(String field) {
    var f = fixture(1);
    var body = body(f);
    switch (field) {
      case "eventId", "buyerCouponId" -> body.put(field, UUID.randomUUID().toString());
      case "expectedEventVersion" -> body.put(field, 0);
      case "mileageAmount" -> body.put(field, 10001);
      case "externalMethod" -> body.putNull(field);
      case "methodAccountRef" -> body.put(field, "   ");
      case "quantity" -> body.put(field, 1.5);
      default -> body.put(field, true);
    }
    problem(
        post(f.buyer(), key(), body),
        field.equals("buyerCouponId") ? 409 : 400,
        field.equals("buyerCouponId") ? "COUPON_NOT_APPLICABLE" : "COMMON_VALIDATION_FAILED");
    assertInventory(f.product(), 1, 0);
  }

  @Test
  @DisplayName("기존 키 재조회도 정지 구매자는 거절하며 새 주문을 만들지 않는다")
  void rejectsSuspendedBuyerAndWrongRole() {
    var f = fixture(2);
    String key = key();
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    jdbc.update("update commerce.buyer_accounts set status='SUSPENDED' where id=?", f.buyer());
    problem(post(f.buyer(), key, body(f)), 401, "COMMON_AUTHENTICATION_REQUIRED");
    problem(get(f.buyer(), "/api/orders"), 401, "COMMON_AUTHENTICATION_REQUIRED");
    var headers = headers(f.buyer());
    headers.set("X-Authenticated-Actor-Kind", "SELLER");
    problem(
        http.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class),
        403,
        "COMMON_ROLE_FORBIDDEN");
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("생성·조회·목록의 런타임 명세는 기본 DB API와 OrderCreate 11필드를 제공한다")
  void servesRealApiDocumentation() {
    var api = http.getForObject("/v3/api-docs", JsonNode.class);
    assertThat(api.path("paths").path("/api/orders").path("post").path("summary").asText())
        .isEqualTo("일반 주문 생성");
    var schema = api.path("components").path("schemas").path("OrderCreate");
    assertThat(schema.path("required").size()).isEqualTo(11);
    assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Order", "OrderPage", "OrderCreate", "Problem", "FieldError"})
  @DisplayName("DB 주문 런타임 명세의 입력·응답 스키마가 정식 계약을 보존한다")
  void generatedSchemasPreserveCanonicalContracts(String name) throws Exception {
    schema(name);
    canonicalSchema(name);
    JsonNode expected = canonicalApi.path("components").path("schemas").path(name);
    JsonNode actual = generatedApi.path("components").path("schemas").path(name);

    assertThat(actual.path("properties").properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrderElementsOf(
            expected.path("properties").properties().stream().map(Map.Entry::getKey).toList());
    assertThat(elements(actual.path("required"))).isEqualTo(elements(expected.path("required")));
    assertThat(actual.get("additionalProperties")).isEqualTo(expected.get("additionalProperties"));
    assertThat(schemaTypes(actual)).isEqualTo(schemaTypes(expected));
    for (var property : expected.path("properties").properties()) {
      String field = property.getKey();
      JsonNode declared = actual.path("properties").path(field);
      assertThat(schemaTypes(declared))
          .as(name + "." + field + " type")
          .isEqualTo(schemaTypes(property.getValue()));
      JsonNode expectedValue = nonNullSchema(property.getValue());
      JsonNode actualValue = nonNullSchema(declared);
      for (String keyword :
          List.of("minimum", "maximum", "minLength", "maxLength", "pattern", "items")) {
        assertThat(actualValue.get(keyword))
            .as(name + "." + field + "." + keyword)
            .isEqualTo(expectedValue.get(keyword));
      }
      if (expectedValue.has("format")) {
        assertThat(actualValue.get("format"))
            .as(name + "." + field + ".format")
            .isEqualTo(expectedValue.get("format"));
      }
      Set<JsonNode> expectedValues = elements(expectedValue.path("enum"));
      Set<JsonNode> actualValues = elements(actualValue.path("enum"));
      actualValues.remove(mapper.getNodeFactory().nullNode());
      assertThat(actualValues).as(name + "." + field + " enum").isEqualTo(expectedValues);
    }
  }

  @ParameterizedTest
  @CsvSource({"/api/orders,post", "/api/orders,get", "/api/orders/{orderId},get"})
  @DisplayName("DB 주문 API의 응답 코드·본문·헤더가 정식 명세와 일치한다")
  void operationsPreserveCanonicalResponsesAndHeaders(String path, String method) throws Exception {
    schema("Order");
    canonicalSchema("Order");
    assertOperation(path, method, generatedApi.path("paths").path(path).path(method));
  }

  private JsonNode resolveComponent(JsonNode api, JsonNode value, String kind) {
    if (!value.has("$ref")) {
      return value;
    }
    String ref = value.path("$ref").asText();
    assertThat(ref).startsWith("#/components/" + kind + "/");
    JsonNode resolved = api.at(ref.substring(1));
    assertThat(resolved.isMissingNode()).as(ref).isFalse();
    return resolved;
  }

  private void assertOperation(String path, String method, JsonNode actual) {
    JsonNode expected = canonicalApi.path("paths").path(path).path(method);
    assertThat(actual.path("operationId")).isEqualTo(expected.path("operationId"));
    Set<String> codes = new HashSet<>();
    expected.path("responses").fieldNames().forEachRemaining(codes::add);
    // 프레임워크의 405/406 및 예상하지 못한 500도 같은 ProblemDetail 계약이다.
    codes.add("default");
    assertThat(actual.path("responses").properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrderElementsOf(codes);
    for (String code : codes) {
      JsonNode response =
          resolveComponent(generatedApi, actual.path("responses").path(code), "responses");
      JsonNode contract =
          resolveComponent(
              canonicalApi,
              expected.path("responses").path(code.equals("default") ? "400" : code),
              "responses");
      assertThat(response.path("content").properties().stream().map(Map.Entry::getKey).toList())
          .as(method + " " + path + " " + code)
          .containsExactlyInAnyOrderElementsOf(
              contract.path("content").properties().stream().map(Map.Entry::getKey).toList());
      for (var media : contract.path("content").properties()) {
        assertThat(response.path("content").path(media.getKey()).path("schema"))
            .isEqualTo(media.getValue().path("schema"));
      }
      assertThat(response.path("headers").properties().stream().map(Map.Entry::getKey).toList())
          .containsExactlyInAnyOrderElementsOf(
              contract.path("headers").properties().stream().map(Map.Entry::getKey).toList());
      for (var header : contract.path("headers").properties()) {
        assertThat(response.path("headers").path(header.getKey()).path("schema"))
            .as(code + " " + header.getKey())
            .isEqualTo(header.getValue().path("schema"));
      }
    }
    if (method.equals("post")) {
      assertThat(
              actual
                  .path("responses")
                  .path("201")
                  .path("headers")
                  .path("Location")
                  .path("required")
                  .asBoolean())
          .isTrue();
      assertThat(
              actual
                  .path("responses")
                  .path("503")
                  .path("headers")
                  .path("Retry-After")
                  .path("required")
                  .asBoolean())
          .isFalse();
      assertThat(
              actual
                  .path("responses")
                  .path("503")
                  .path("headers")
                  .path("Retry-After")
                  .path("description")
                  .asText())
          .isEqualTo(
              expected
                  .path("responses")
                  .path("503")
                  .path("headers")
                  .path("Retry-After")
                  .path("description")
                  .asText());
    }
  }

  @ParameterizedTest
  @CsvSource({"/api/orders,post", "/api/orders,get", "/api/orders/{orderId},get"})
  @DisplayName("DB 주문 API의 파라미터 필수 여부·범위가 정식 명세와 일치한다")
  void operationsPreserveCanonicalParameterContracts(String path, String method) throws Exception {
    schema("Order");
    canonicalSchema("Order");
    JsonNode actual = generatedApi.path("paths").path(path).path(method);
    for (JsonNode reference :
        canonicalApi.path("paths").path(path).path(method).path("parameters")) {
      JsonNode expected = resolveComponent(canonicalApi, reference, "parameters");
      JsonNode parameter =
          java.util.stream.StreamSupport.stream(actual.path("parameters").spliterator(), false)
              .map(value -> resolveComponent(generatedApi, value, "parameters"))
              .filter(
                  value ->
                      value.path("name").equals(expected.path("name"))
                          && value.path("in").equals(expected.path("in")))
              .findFirst()
              .orElseThrow();
      assertThat(parameter).isNotNull();
      assertThat(parameter.path("required").asBoolean())
          .isEqualTo(expected.path("required").asBoolean());
      for (var keyword : expected.path("schema").properties()) {
        assertThat(parameter.path("schema").get(keyword.getKey()))
            .as(expected.path("name") + "." + keyword.getKey())
            .isEqualTo(keyword.getValue());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 100, 101})
  @DisplayName("DB 주문의 알 수 없는 필드 오류는 필드명 길이 계약을 지킨다")
  void unknownFieldErrorsRespectFieldNameBounds(int length) throws Exception {
    var f = fixture(1);
    String field = "f".repeat(length);
    var response = post(f.buyer(), key(), body(f).put(field, 1));

    problem(response, 400, "COMMON_VALIDATION_FAILED");
    matches("Problem", response.getBody());
    assertThat(response.getBody().path("errors").get(0).path("field").asText())
        .isEqualTo(length == 100 ? field : "request");
  }

  @Test
  @DisplayName("실제 주문 오류는 수신 추적 ID를 보존하고 명세에 없는 메서드는 거절한다")
  void problemCarriesInboundTraceAndRejectsUndeclaredMethods() throws Exception {
    var headers = new HttpHeaders();
    String traceId = "0123456789abcdef0123456789abcdef";
    headers.set("traceparent", "00-" + traceId + "-0123456789abcdef-01");
    var response =
        http.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

    problem(response, 401, "COMMON_AUTHENTICATION_REQUIRED");
    assertThat(response.getBody().path("traceId").asText()).isEqualTo(traceId);
    matches("Problem", response.getBody());
    var unsupported =
        http.exchange(
            "/api/orders",
            HttpMethod.PUT,
            new HttpEntity<>(headers(UUID.randomUUID())),
            JsonNode.class);
    problem(unsupported, 404, "COMMON_RESOURCE_NOT_FOUND");
    matches("Problem", unsupported.getBody());
  }

  private Set<JsonNode> elements(JsonNode values) {
    Set<JsonNode> result = new HashSet<>();
    values.forEach(result::add);
    return result;
  }

  private Set<String> schemaTypes(JsonNode definition) {
    Set<String> result = new HashSet<>();
    JsonNode type = definition.path("type");
    if (type.isArray()) {
      type.forEach(value -> result.add(value.asText()));
    } else if (type.isTextual()) {
      result.add(type.asText());
    }
    for (String union : List.of("anyOf", "oneOf")) {
      definition.path(union).forEach(value -> result.addAll(schemaTypes(value)));
    }
    return result;
  }

  private JsonNode nonNullSchema(JsonNode definition) {
    for (JsonNode option : definition.path("anyOf")) {
      if (!option.path("type").asText().equals("null")) {
        return option;
      }
    }
    return definition;
  }

  private Schema canonicalSchema(String name) throws Exception {
    if (canonicalSchemas == null) {
      try (var input = getClass().getResourceAsStream("/contracts/openapi.json")) {
        canonicalApi = mapper.readTree(input);
      }
      canonicalSchemas =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder ->
                  builder.schemas(Map.of("urn:commerce:canonical", canonicalApi.toString())));
    }
    return canonicalSchemas.getSchema(
        SchemaLocation.of("urn:commerce:canonical#/components/schemas/" + name));
  }

  private Schema schema(String name) {
    if (schemas == null) {
      generatedApi = http.getForObject("/v3/api-docs", JsonNode.class);
      assertThat(generatedApi.path("openapi").asText()).startsWith("3.1.");
      schemas =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder -> builder.schemas(Map.of("urn:commerce:orders", generatedApi.toString())));
    }
    return schemas.getSchema(SchemaLocation.of("urn:commerce:orders#/components/schemas/" + name));
  }

  private Fixture fixture(long stock) {
    UUID buyer = buyer();
    UUID address = addresses.register(buyer, key(), SHIPPING, true).id();
    UUID seller = UUID.randomUUID();
    UUID product =
        catalog.register(seller, key(), "실제 주문 시험 상품", 10000, ProductStatus.ON_SALE).getId();
    catalog.adjustAvailable(seller, product, key(), stock, 0, "시험 입고");
    return new Fixture(buyer, address, seller, product);
  }

  private UUID buyer() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','구매자','ACTIVE',now(),now(),0)",
        id,
        id + "@example.com");
    return id;
  }

  private ObjectNode body(Fixture f) {
    var body =
        mapper
            .createObjectNode()
            .put("productId", f.product().toString())
            .put("quantity", 1)
            .put("mileageAmount", 0)
            .put("addressId", f.address().toString())
            .put("expectedProductVersion", 0)
            .put("expectedTotalAmount", 10000)
            .put("externalMethod", "CREDIT_CARD")
            .put("methodAccountRef", "test-card");
    body.putNull("eventId").putNull("buyerCouponId").putNull("expectedEventVersion");
    return body;
  }

  private ResponseEntity<JsonNode> post(UUID buyer, String key, ObjectNode body) {
    var headers = headers(buyer);
    headers.set("Idempotency-Key", key);
    return http.exchange(
        "/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
  }

  private ResponseEntity<JsonNode> get(UUID buyer, String url) {
    return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(buyer)), JsonNode.class);
  }

  private static HttpHeaders headers(UUID buyer) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(GATEWAY_CREDENTIAL);
    headers.set("X-Authenticated-Actor-Kind", "BUYER");
    headers.set("X-Authenticated-Actor-Id", buyer.toString());
    return headers;
  }

  private static void problem(ResponseEntity<JsonNode> response, int status, String code) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    assertThat(
            response
                .getHeaders()
                .getContentType()
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .isTrue();
    assertThat(response.getBody().path("status").asInt()).isEqualTo(status);
    assertThat(response.getBody().path("instance").asText()).isNotBlank();
    assertThat(response.getBody().path("traceId").asText()).isNotBlank();
  }

  private void matches(String name, JsonNode node) throws Exception {
    var contracts = new java.util.ArrayList<Schema>();
    contracts.add(canonicalSchema(name));
    if (Set.of("Order", "OrderPage", "OrderCreate", "Problem", "FieldError").contains(name)) {
      contracts.add(schema(name));
    }
    for (Schema contract : contracts) {
      assertThat(
              contract.validate(
                  node.toString(),
                  InputFormat.JSON,
                  context ->
                      context.executionConfig(config -> config.formatAssertionsEnabled(true))))
          .as(name)
          .isEmpty();
    }
  }

  private long count(String table, String field, UUID id) {
    return jdbc.queryForObject(
        "select count(*) from commerce." + table + " where " + field + "=?", Long.class, id);
  }

  private void assertInventory(UUID product, long available, long reserved) {
    assertThat(
            jdbc.queryForMap(
                "select available_qty,reserved_qty,sold_qty from commerce.product_inventory where product_id=?",
                product))
        .containsEntry("available_qty", available)
        .containsEntry("reserved_qty", reserved)
        .containsEntry("sold_qty", 0L);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Fixture(UUID buyer, UUID address, UUID seller, UUID product) {}
}
