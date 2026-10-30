package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.PaymentApplication;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.service.AcceptOrderPaymentRequestService;
import com.commercesettle.payment.application.service.QuarantineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** 명시적으로 실행하는 실험. 기본 JUnit 검사를 생략하지 않고 기존 test runtime을 재사용한다. */
public final class KafkaCapacityExperiment {
  private static final String TOPIC = "commerce-settle.commerce.v1";
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final AtomicReference<Measurements> CURRENT = new AtomicReference<>();
  private static final long SECOND = 1_000_000_000L;
  private static final String TEMPLATE =
      """
      {"eventId":"%s","eventType":"OrderPaymentRequested","eventVersion":1,
      "occurredAt":"2026-10-30T00:00:00Z","aggregateId":"%s","aggregateVersion":0,
      "payload":{"orderId":"%s","buyerId":"00000000-0000-0000-0000-000000000002",
      "sellerId":"00000000-0000-0000-0000-000000000003","paymentCycleId":"%s",
      "productId":"00000000-0000-0000-0000-000000000005","orderVersion":0,
      "status":"PAYMENT_PENDING","policyVersion":"capacity-v1","paymentWaitUntil":"2100-01-01T00:00:00Z",
      "externalMethod":"CREDIT_CARD","methodAccountRef":"synthetic-card","grossAmount":10000,
      "couponAmount":0,"totalAmount":10000,"mileageAmount":0,"externalAmount":10000,
      "sellerCouponAmount":0,"platformCouponAmount":0,"settlementBaseAmount":10000}}
      """;

  private KafkaCapacityExperiment() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected preregistered plan and output paths");
    }
    Path planPath = Path.of(args[0]);
    Path output = Path.of(args[1]);
    if (Files.exists(output)) {
      throw new IllegalArgumentException("A previous experiment output must not be overwritten");
    }
    JsonNode plan = JSON.readTree(Files.readAllBytes(planPath));
    var result = new LinkedHashMap<String, Object>();
    result.put("boundary", plan.path("boundary").asText());
    result.put("planSha256", hash(Files.readAllBytes(planPath)));
    result.put("startedAt", java.time.Instant.now().toString());
    result.put("javaVersion", System.getProperty("java.version"));
    result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    result.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
    result.put(
        "operatingSystem", System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    result.put("kafkaClientVersion", org.apache.kafka.common.utils.AppInfoParser.getVersion());
    result.put("springBootVersion", org.springframework.boot.SpringBootVersion.getVersion());
    result.put("cpuMeasurementScope", "발생기와 Consumer가 같은 JVM을 사용한다. 순수 Consumer CPU로 분리하지 않는다.");
    result.put("runningClasses", classHashes());
    result.put("harnessSelfChecks", verifyHarnessContract());
    result.put("interpretation", "수신 경계의 탐색 비교이며 결제 확정 TPS·최적 설정의 증거가 아니다.");
    var runs = new ArrayList<Map<String, Object>>();
    result.put("runs", runs);
    write(output, result);
    requireDisk(plan.path("minFreeDiskFraction").asDouble());
    var postgres =
        new PostgreSQLContainer<>(plan.path("runtime").path("databaseImage").asText())
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.getHostConfig()
                        .withMemory(plan.path("runtime").path("databaseMemoryBytes").asLong())
                        .withNanoCPUs(1_000_000_000L));
    var kafka =
        new KafkaContainer(plan.path("runtime").path("brokerImage").asText())
            .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.getHostConfig()
                        .withMemory(plan.path("runtime").path("brokerMemoryBytes").asLong())
                        .withNanoCPUs(1_000_000_000L));
    try (postgres;
        kafka) {
      postgres.start();
      result.put("postgresContainerId", postgres.getContainerId());
      write(output, result);
      kafka.start();
      result.put("containerIds", List.of(postgres.getContainerId(), kafka.getContainerId()));
      write(output, result);
      int number = 0;
      for (int block = 0; block < plan.path("blocks").size(); block++) {
        for (JsonNode name : plan.path("blocks").get(block)) {
          JsonNode configuration = configuration(plan, name.asText());
          var run = newTrial(runs, "diagnostic");
          runTrial(
              plan,
              configuration,
              postgres,
              kafka,
              ++number,
              block,
              plan.path("diagnosticSeconds").asInt(),
              false,
              run);
          write(output, result);
          System.out.println(
              JSON.writeValueAsString(
                  Map.of(
                      "trial",
                      number,
                      "configuration",
                      name.asText(),
                      "result",
                      run.get("verdict"))));
        }
      }
      var candidates = new ArrayList<String>();
      for (JsonNode config : plan.path("configurations")) {
        String id = config.path("id").asText();
        var matching =
            runs.stream()
                .filter(
                    run ->
                        "diagnostic".equals(run.get("phase"))
                            && id.equals(run.get("configuration")))
                .toList();
        if (matching.size() == plan.path("blocks").size()
            && matching.stream().allMatch(run -> Boolean.TRUE.equals(run.get("targetPassed")))) {
          candidates.add(id);
        }
      }
      result.put("confirmationCandidates", candidates);
      boolean observed = false;
      for (String candidate : candidates) {
        var confirmation = newTrial(runs, "confirmation");
        runTrial(
            plan,
            configuration(plan, candidate),
            postgres,
            kafka,
            ++number,
            10,
            plan.path("confirmationSeconds").asInt(),
            false,
            confirmation);
        write(output, result);
        if (!Boolean.TRUE.equals(confirmation.get("targetPassed"))) {
          continue;
        }
        int duration =
            15
                + plan.path("recoveryPauseSeconds").asInt()
                + plan.path("recoveryDrainSeconds").asInt()
                + 60;
        var recovery = newTrial(runs, "recovery");
        runTrial(
            plan,
            configuration(plan, candidate),
            postgres,
            kafka,
            ++number,
            11,
            duration,
            true,
            recovery);
        write(output, result);
        if (Boolean.TRUE.equals(recovery.get("recoveryPassed"))) {
          result.put("observedConfiguration", candidate);
          observed = true;
          break;
        }
      }
      result.put("finishedAt", java.time.Instant.now().toString());
      result.put(
          "status",
          candidates.isEmpty()
              ? "diagnostic-target-not-met"
              : observed ? "capacity-and-recovery-observed" : "long-run-target-not-met");
      write(output, result);
    } catch (Exception failure) {
      result.put("status", "execution-failed");
      result.put("failureClass", failure.getClass().getName());
      write(output, result);
      throw failure;
    } finally {
      try {
        result.put(
            "containersRunningAfterClose",
            Map.of("postgres", postgres.isRunning(), "kafka", kafka.isRunning()));
      } catch (RuntimeException failure) {
        result.put("containersRunningAfterClose", "unverified");
      }
      write(output, result);
    }
  }

  private static void runTrial(
      JsonNode plan,
      JsonNode config,
      PostgreSQLContainer<?> postgres,
      KafkaContainer kafka,
      int number,
      int block,
      int seconds,
      boolean recovery,
      Map<String, Object> run)
      throws Exception {
    String database = "capacity_trial_" + number;
    String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
    url += (url.contains("?") ? "&" : "?") + "socketTimeout=5&connectTimeout=5";
    String group = "capacity-" + UUID.randomUUID();
    int partitions = config.path("partitions").asInt();
    int consumers = config.path("consumers").asInt();
    int rate = plan.path("offeredRecordsPerSecond").asInt();
    int warmupCount = rate * plan.path("warmupSeconds").asInt();
    long prefix = plan.path("seed").asLong() ^ ((long) block << 32);
    var metrics = new Measurements(prefix, Math.multiplyExact(rate, seconds));
    run.put("number", number);
    run.put("block", block);
    run.put("configuration", config.path("id").asText());
    run.put("partitions", partitions);
    run.put("consumers", consumers);
    requireDisk(plan.path("minFreeDiskFraction").asDouble());
    run.put(
        "dockerDiskFreeFractionAtStart",
        requireContainerDisk(postgres, plan.path("minFreeDiskFraction").asDouble()));
    var samples = new ArrayList<Map<String, Object>>();
    run.put("samples", samples);
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
    try (var connection =
            DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement();
        InputStream ddl = KafkaCapacityExperiment.class.getResourceAsStream("/db/payment.sql")) {
      if (ddl == null) {
        throw new IllegalStateException("Missing canonical Payment DDL");
      }
      statement.execute(new String(ddl.readAllBytes(), StandardCharsets.UTF_8));
    }
    try (Admin admin = admin(kafka)) {
      if (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
        admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        waitUntil(() -> !admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(TOPIC), 20);
      }
      admin
          .createTopics(List.of(new NewTopic(TOPIC, partitions, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
      var initial = new HashMap<TopicPartition, OffsetAndMetadata>();
      for (int p = 0; p < partitions; p++) {
        initial.put(new TopicPartition(TOPIC, p), new OffsetAndMetadata(0));
      }
      admin.alterConsumerGroupOffsets(group, initial).all().get(10, TimeUnit.SECONDS);
      CURRENT.set(metrics);
      var properties = new LinkedHashMap<String, String>();
      properties.put("spring.datasource.url", url);
      properties.put("spring.datasource.username", postgres.getUsername());
      properties.put("spring.datasource.password", postgres.getPassword());
      properties.put("spring.datasource.hikari.connection-timeout", "5000");
      properties.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
      properties.put("spring.kafka.consumer.group-id", group);
      properties.put("spring.kafka.listener.concurrency", Integer.toString(consumers));
      properties.put("app.kafka.order-payment-requested.enabled", "true");
      properties.put("app.kafka.quarantine.enabled", "true");
      properties.put("app.payment-request-processor.enabled", "false");
      properties.put("management.tracing.sampling.probability", "0");
      properties.put("logging.level.root", "WARN");
      properties.put("spring.main.banner-mode", "off");
      try (var context =
              new SpringApplicationBuilder(PaymentApplication.class, Instrumentation.class)
                  .web(WebApplicationType.NONE)
                  .run(
                      properties.entrySet().stream()
                          .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                          .toArray(String[]::new));
          KafkaProducer<String, byte[]> producer = producer(kafka)) {
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        var listeners = context.getBean(KafkaListenerEndpointRegistry.class);
        waitForAssignment(admin, group, consumers, partitions);
        if (producer.partitionsFor(TOPIC).size() != partitions) {
          throw new IllegalStateException(
              "Producer partition metadata differs from preregistration");
        }
        var warmup = new Measurements(prefix, warmupCount);
        long warmupStart = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        produce(
            producer,
            warmup,
            rate,
            metrics.capacity,
            plan.path("maxInFlight").asInt(),
            new AtomicLong(warmupStart + plan.path("warmupSeconds").asInt() * SECOND),
            warmupStart,
            new AtomicBoolean());
        producer.flush();
        waitUntil(
            () -> lag(admin, group, partitions).values().stream().allMatch(value -> value == 0),
            plan.path("maxDrainSeconds").asInt());
        if (warmup.failed.get() != 0 || warmup.sent.get() != warmupCount) {
          throw new IllegalStateException("Warmup generator did not deliver its cohort");
        }
        long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        var stopAt = new AtomicLong(start + seconds * SECOND);
        var stopped = new AtomicBoolean();
        var inputClosed = new AtomicBoolean();
        var control = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        run.put("recovery", control);
        var pool = (HikariDataSource) context.getBean(DataSource.class);
        var executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> generator = null;
        java.util.concurrent.Future<?> outage = null;
        boolean measurementCompleted = false;
        try {
          generator =
              executor.submit(
                  () ->
                      produce(
                          producer,
                          metrics,
                          rate,
                          0,
                          plan.path("maxInFlight").asInt(),
                          stopAt,
                          start,
                          inputClosed));
          outage =
              recovery
                  ? executor.submit(
                      () -> {
                        try {
                          parkUntil(start + 15 * SECOND);
                          var listener = listeners.getListenerContainer("orderPaymentRequested");
                          var completed = new CountDownLatch(1);
                          listener.stop(completed::countDown);
                          if (!completed.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Consumer stop timed out");
                          }
                          long pause = System.nanoTime();
                          control.put("pauseStartedElapsedMs", (pause - start) / 1_000_000);
                          parkUntil(pause + plan.path("recoveryPauseSeconds").asInt() * SECOND);
                          listener.start();
                          waitForAssignment(admin, group, consumers, partitions);
                          long resume = System.nanoTime();
                          control.put("assignmentResumedElapsedMs", (resume - start) / 1_000_000);
                          control.put("assignmentResumedElapsedNanos", resume - start);
                          control.put("pauseDurationMs", (resume - pause) / 1_000_000);
                          stopAt.set(resume + plan.path("recoveryDrainSeconds").asInt() * SECOND);
                        } catch (Exception failure) {
                          stopped.set(true);
                          stopAt.set(System.nanoTime());
                          throw new IllegalStateException("Recovery control failed");
                        }
                      })
                  : CompletableFuture.completedFuture(null);
          long nextSample = start;
          while (!generator.isDone()) {
            parkUntil(nextSample);
            var offsets = lag(admin, group, partitions);
            long backlog = offsets.values().stream().mapToLong(Long::longValue).sum();
            long limit = recovery ? 400000 : plan.path("maxBacklog").asLong();
            var sample = new LinkedHashMap<String, Object>();
            sample.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
            sample.put("sent", metrics.sent.get());
            sample.put("producerAcked", metrics.acked.get());
            sample.put("uniqueDurable", metrics.durable.get());
            sample.put("lag", offsets);
            sample.put("jdbcActive", pool.getHikariPoolMXBean().getActiveConnections());
            sample.put("jdbcPending", pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
            sample.put("postgresResources", containerResources(postgres));
            sample.put("brokerResources", containerResources(kafka));
            sample.put(
                "postgresStatistics",
                jdbc.queryForMap(
                    "SELECT xact_commit,xact_rollback,blks_read,blks_hit,temp_bytes,deadlocks,"
                        + "blk_read_time,blk_write_time,stats_reset FROM pg_stat_database WHERE datname=current_database()"));
            sample.put(
                "postgresActiveWaits",
                jdbc.queryForList(
                    "SELECT coalesce(wait_event_type,'CPU_OR_RUNNABLE') AS kind,count(*) AS count "
                        + "FROM pg_stat_activity WHERE datname=current_database() AND state='active' "
                        + "AND pid<>pg_backend_pid() GROUP BY wait_event_type"));
            sample.put(
                "heapUsedBytes",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            sample.put(
                "jvmCpuSeconds",
                ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos()
                    / (double) SECOND);
            sample.put(
                "inbox",
                jdbc.queryForMap(
                    "SELECT count(*) AS count, "
                        + "coalesce(extract(epoch FROM clock_timestamp()-min(created_at)),0) AS oldest_waiting_seconds "
                        + "FROM payment.inbox WHERE status='WAITING'"));
            sample.put(
                "quarantined",
                jdbc.queryForObject("SELECT count(*) FROM payment.inbox_quarantines", Long.class));
            sample.put(
                "unfinishedDlt",
                jdbc.queryForObject(
                    "SELECT count(*) FROM payment.inbox_quarantine_deliveries WHERE status<>'PUBLISHED'",
                    Long.class));
            sample.put(
                "dockerDiskFreeFraction",
                requireContainerDisk(postgres, plan.path("minFreeDiskFraction").asDouble()));
            samples.add(sample);
            if (backlog > limit) {
              stopped.set(true);
              stopAt.set(System.nanoTime());
              run.put("stopReason", "backlog-limit");
              break;
            }
            if (recovery
                && control.containsKey("assignmentResumedElapsedMs")
                && backlog == 0
                && !control.containsKey("firstCaughtUpElapsedMs")) {
              long caughtUp = System.nanoTime() - start;
              control.put("firstCaughtUpElapsedMs", caughtUp / 1_000_000);
              control.put("firstCaughtUpElapsedNanos", caughtUp);
            }
            requireDisk(plan.path("minFreeDiskFraction").asDouble());
            nextSample += plan.path("sampleSeconds").asInt() * SECOND;
          }
          generator.get(10, TimeUnit.SECONDS);
          if (stopped.get()) {
            outage.cancel(true);
          } else {
            outage.get(10, TimeUnit.SECONDS);
          }
          measurementCompleted = true;
        } catch (Exception failure) {
          run.put("failureClass", failure.getClass().getName());
          throw failure;
        } finally {
          if (!measurementCompleted) {
            stopped.set(true);
            stopAt.set(Math.min(stopAt.get(), System.nanoTime()));
          }
          boolean terminated = stopWorkers(executor, generator, outage, inputClosed);
          run.put("workersTerminated", terminated);
          if (!terminated) {
            throw new IllegalStateException(
                "Experiment workers did not stop within the fixed budget");
          }
        }
        long durationNanos = stopAt.get() - start;
        long durableAtEnd = metrics.completedBefore(stopAt.get());
        producer.flush();
        boolean drained =
            waitForDrain(admin, group, partitions, plan.path("maxDrainSeconds").asInt());
        listeners.stop();
        int expected = cohortSize(start, stopAt.get(), rate, metrics.capacity);
        long inbox = jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Long.class);
        long unique =
            jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM payment.inbox", Long.class);
        long quarantined =
            jdbc.queryForObject("SELECT count(*) FROM payment.inbox_quarantines", Long.class);
        long unfinishedDlt =
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.inbox_quarantine_deliveries WHERE status<>'PUBLISHED'",
                Long.class);
        boolean bytesMatch = verifyBytes(jdbc, metrics, warmupCount);
        boolean correct =
            inbox == metrics.acked.get() + warmupCount
                && inbox == unique
                && quarantined == 0
                && unfinishedDlt == 0
                && bytesMatch;
        long late = metrics.lateSchedules.get();
        boolean offered =
            !stopped.get()
                && metrics.sent.get() == expected
                && metrics.failed.get() == 0
                && metrics.acked.get() == expected
                && late <= expected * .001;
        boolean passed = offered && correct && drained && durableAtEnd >= expected * .999;
        run.put("elapsedSeconds", durationNanos / (double) SECOND);
        run.put("scheduled", expected);
        run.put("sent", metrics.sent.get());
        run.put("producerAcked", metrics.acked.get());
        run.put("producerFailures", metrics.failed.get());
        run.put("durableAtMeasurementEnd", durableAtEnd);
        run.put("uniqueDurableAfterDrain", metrics.durable.get());
        run.put("durablePerSecond", durableAtEnd * SECOND / (double) durationNanos);
        run.put("duplicateDeliveries", metrics.duplicates.get());
        run.put("correctnessPassed", correct);
        run.put("generatorValid", offered);
        run.put("drained", drained);
        run.put("rawBytesMatched", bytesMatch);
        run.put("lateSchedulesOver100ms", late);
        run.put("missingAfterDrain", Math.max(0, expected - metrics.durable.get()));
        run.put("latencyPopulation", "완료한 측정 cohort; 미완료 수는 missingAfterDrain으로 별도 보고");
        run.put("completedCommitLatencyMs", percentiles(metrics.latencies));
        run.put("producerAckLatencyMs", percentiles(metrics.producerLatencies));
        run.put("scheduleLatenessMs", percentiles(metrics.scheduleLateness));
        run.put("handoffTransactionMs", percentiles(metrics.transactions));
        run.put("targetPassed", passed);
        run.put(
            "verdict",
            !offered ? "invalid-or-aborted-load" : passed ? "target-observed" : "target-not-met");
        if (recovery) {
          Object caughtUp = control.get("firstCaughtUpElapsedNanos");
          Object resumed = control.get("assignmentResumedElapsedNanos");
          boolean recovered =
              caughtUp instanceof Long caught
                  && resumed instanceof Long resume
                  && caught >= resume
                  && caught - resume <= plan.path("recoveryDrainSeconds").asLong() * SECOND;
          run.put("recoveryPassed", recovered && passed);
        }
      }
      waitUntil(
          () ->
              admin
                  .describeConsumerGroups(List.of(group))
                  .all()
                  .get(5, TimeUnit.SECONDS)
                  .get(group)
                  .members()
                  .isEmpty(),
          15);
      admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
    } finally {
      run.put("sentAtExit", metrics.sent.get());
      run.put("producerAckedAtExit", metrics.acked.get());
      run.put("durableAtExit", metrics.durable.get());
      CURRENT.set(null);
      try (var connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
      }
    }
  }

  private static Map<String, Object> newTrial(List<Map<String, Object>> runs, String phase) {
    var run = new LinkedHashMap<String, Object>();
    run.put("phase", phase);
    run.put("verdict", "not-completed");
    runs.add(run);
    return run;
  }

  private static void produce(
      KafkaProducer<String, byte[]> producer,
      Measurements metrics,
      int rate,
      int indexOffset,
      int maxInFlight,
      AtomicLong stopAt,
      long start,
      AtomicBoolean inputClosed) {
    var slots = new Semaphore(maxInFlight);
    for (int i = 0; i < metrics.capacity; i++) {
      long deadline = start + i * SECOND / rate;
      if (inputClosed.get() || i >= cohortSize(start, stopAt.get(), rate, metrics.capacity)) {
        break;
      }
      parkUntil(deadline);
      if (inputClosed.get()) {
        break;
      }
      if (System.nanoTime() - deadline > TimeUnit.MILLISECONDS.toNanos(100)) {
        metrics.lateSchedules.incrementAndGet();
        metrics.failed.incrementAndGet();
        break;
      }
      if (!slots.tryAcquire()) {
        metrics.failed.incrementAndGet();
        continue;
      }
      int index = i;
      metrics.starts.set(index, System.nanoTime());
      long lateness = Math.max(1, metrics.starts.get(index) - deadline);
      metrics.scheduleLateness.set(index, lateness);
      if (lateness > TimeUnit.MILLISECONDS.toNanos(100)) {
        metrics.lateSchedules.incrementAndGet();
      }
      try {
        long sequence = (long) index + indexOffset;
        var record =
            new ProducerRecord<>(
                TOPIC,
                orderId(metrics.prefix, sequence).toString(),
                body(metrics.prefix, sequence));
        producer.send(
            record,
            (metadata, failure) -> {
              if (failure == null) {
                metrics.producerLatencies.set(index, System.nanoTime() - metrics.starts.get(index));
                metrics.acked.incrementAndGet();
              } else {
                metrics.failed.incrementAndGet();
              }
              slots.release();
            });
        metrics.sent.incrementAndGet();
      } catch (RuntimeException failure) {
        metrics.failed.incrementAndGet();
        slots.release();
      }
    }
  }

  private static int cohortSize(long start, long cutoff, int rate, int capacity) {
    if (cutoff <= start) {
      return 0;
    }
    return (int) Math.min(capacity, Math.ceilDiv(Math.multiplyExact(cutoff - start, rate), SECOND));
  }

  private static Map<String, Boolean> verifyHarnessContract() throws Exception {
    if (cohortSize(100, 100, 1000, 2000) != 0
        || cohortSize(100, 101, 1000, 2000) != 1
        || cohortSize(0, 1_000_000, 1000, 2000) != 1
        || cohortSize(0, 1_000_001, 1000, 2000) != 2
        || cohortSize(0, SECOND, 1000, 2000) != 1000
        || cohortSize(0, SECOND + 1, 1000, 2000) != 1001) {
      throw new IllegalStateException("Cohort cutoff contract failed");
    }
    var executor = Executors.newFixedThreadPool(2);
    var inputClosed = new AtomicBoolean();
    var started = new CountDownLatch(2);
    Runnable task =
        () -> {
          started.countDown();
          while (!inputClosed.get() && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
          }
        };
    var producer = executor.submit(task);
    var controller = executor.submit(task);
    try {
      if (!started.await(2, TimeUnit.SECONDS)
          || !stopWorkers(executor, producer, controller, inputClosed)
          || !inputClosed.get()
          || !executor.isTerminated()) {
        throw new IllegalStateException("Guard-failure cancellation contract failed");
      }
    } finally {
      inputClosed.set(true);
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
    }
    return Map.of("cohortCutoffs", true, "guardFailureStopsWorkers", true);
  }

  private static boolean stopWorkers(
      java.util.concurrent.ExecutorService executor,
      java.util.concurrent.Future<?> generator,
      java.util.concurrent.Future<?> outage,
      AtomicBoolean inputClosed)
      throws InterruptedException {
    inputClosed.set(true);
    if (generator != null) {
      generator.cancel(true);
    }
    if (outage != null) {
      outage.cancel(true);
    }
    executor.shutdownNow();
    return executor.awaitTermination(15, TimeUnit.SECONDS);
  }

  private static byte[] body(long prefix, long index) {
    UUID order = orderId(prefix, index);
    return TEMPLATE
        .formatted(new UUID(prefix, index), order, order, order)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static UUID orderId(long prefix, long index) {
    return new UUID(prefix ^ 0x12345678L, index);
  }

  private static boolean verifyBytes(JdbcTemplate jdbc, Measurements metrics, int warmupCount)
      throws Exception {
    MessageDigest expected = MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < metrics.capacity; i++) {
      if (metrics.starts.get(i) != 0) {
        expected.update(body(metrics.prefix, i));
      }
    }
    for (int i = 0; i < warmupCount; i++) {
      expected.update(body(metrics.prefix, (long) metrics.capacity + i));
    }
    MessageDigest actual = MessageDigest.getInstance("SHA-256");
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.setFetchSize(1000);
        statement.setQueryTimeout(120);
        try (var rows =
            statement.executeQuery("SELECT raw_body FROM payment.inbox ORDER BY event_id")) {
          while (rows.next()) {
            actual.update(rows.getBytes(1));
          }
        }
      }
      connection.commit();
    }
    return Arrays.equals(expected.digest(), actual.digest());
  }

  private static JsonNode configuration(JsonNode plan, String id) {
    for (JsonNode config : plan.path("configurations")) {
      if (id.equals(config.path("id").asText())) {
        return config;
      }
    }
    throw new IllegalArgumentException("Unknown preregistered configuration");
  }

  private static Admin admin(KafkaContainer kafka) {
    return Admin.create(
        Map.of(
            "bootstrap.servers",
            kafka.getBootstrapServers(),
            "default.api.timeout.ms",
            10000,
            "request.timeout.ms",
            5000));
  }

  private static KafkaProducer<String, byte[]> producer(KafkaContainer kafka) {
    return new KafkaProducer<>(
        Map.of(
            "bootstrap.servers",
            kafka.getBootstrapServers(),
            "acks",
            "all",
            "enable.idempotence",
            true,
            "linger.ms",
            5,
            "max.block.ms",
            1000,
            "delivery.timeout.ms",
            10000,
            "request.timeout.ms",
            5000,
            "buffer.memory",
            16 * 1024 * 1024),
        new StringSerializer(),
        new ByteArraySerializer());
  }

  private static Map<String, Long> lag(Admin admin, String group, int partitions) throws Exception {
    var request = new HashMap<TopicPartition, OffsetSpec>();
    for (int p = 0; p < partitions; p++) {
      request.put(new TopicPartition(TOPIC, p), OffsetSpec.latest());
    }
    var ends = admin.listOffsets(request).all().get(10, TimeUnit.SECONDS);
    var committed =
        admin
            .listConsumerGroupOffsets(group)
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
    var result = new LinkedHashMap<String, Long>();
    for (int p = 0; p < partitions; p++) {
      var partition = new TopicPartition(TOPIC, p);
      result.put(
          Integer.toString(p),
          Math.max(
              0,
              ends.get(partition).offset()
                  - committed.getOrDefault(partition, new OffsetAndMetadata(0)).offset()));
    }
    return result;
  }

  private static void waitForAssignment(Admin admin, String group, int consumers, int partitions)
      throws Exception {
    waitUntil(
        () -> {
          var members =
              admin
                  .describeConsumerGroups(List.of(group))
                  .all()
                  .get(10, TimeUnit.SECONDS)
                  .get(group)
                  .members();
          return members.size() == consumers
              && members.stream()
                      .mapToInt(member -> member.assignment().topicPartitions().size())
                      .sum()
                  == partitions;
        },
        30);
  }

  private static boolean waitForDrain(Admin admin, String group, int partitions, int seconds)
      throws Exception {
    long deadline = System.nanoTime() + seconds * SECOND;
    do {
      if (lag(admin, group, partitions).values().stream().allMatch(value -> value == 0)) {
        return true;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static void waitUntil(Condition condition, int seconds) throws Exception {
    long deadline = System.nanoTime() + seconds * SECOND;
    while (!condition.test()) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("Experiment setup or reconciliation timed out");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
    }
  }

  private static void parkUntil(long deadline) {
    long remaining;
    while ((remaining = deadline - System.nanoTime()) > 0) {
      if (Thread.currentThread().isInterrupted()) {
        throw new IllegalStateException("Experiment interrupted");
      }
      LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
    }
  }

  private static Map<String, Double> percentiles(AtomicLongArray values) {
    long[] sorted = new long[values.length()];
    int count = 0;
    for (int i = 0; i < values.length(); i++) {
      if (values.get(i) > 0) {
        sorted[count++] = values.get(i);
      }
    }
    Arrays.sort(sorted, 0, count);
    if (count == 0) {
      return Map.of();
    }
    return Map.of(
        "p50",
        sorted[(count - 1) / 2] / 1_000_000.0,
        "p95",
        sorted[(int) Math.ceil(count * .95) - 1] / 1_000_000.0,
        "p99",
        sorted[(int) Math.ceil(count * .99) - 1] / 1_000_000.0,
        "max",
        sorted[count - 1] / 1_000_000.0);
  }

  private static void requireDisk(double margin) throws Exception {
    var store = Files.getFileStore(Path.of("."));
    if (store.getUsableSpace() / (double) store.getTotalSpace() < margin) {
      throw new IllegalStateException("Preregistered disk margin not available");
    }
  }

  private static double requireContainerDisk(PostgreSQLContainer<?> postgres, double margin)
      throws Exception {
    var result = postgres.execInContainer("df", "-Pk", "/var/lib/postgresql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("Docker data disk margin could not be observed");
    }
    String[] lines = result.getStdout().strip().split("\\R");
    String[] columns = lines[lines.length - 1].strip().split("\\s+");
    double fraction = Double.parseDouble(columns[3]) / Double.parseDouble(columns[1]);
    if (fraction < margin) {
      throw new IllegalStateException("Preregistered Docker disk margin not available");
    }
    return fraction;
  }

  private static Map<String, Long> containerResources(
      org.testcontainers.containers.GenericContainer<?> container) throws Exception {
    var observation =
        container.execInContainer(
            "cat", "/sys/fs/cgroup/cpu.stat", "/sys/fs/cgroup/memory.current");
    if (observation.getExitCode() != 0) {
      throw new IllegalStateException("Container resource counters unavailable");
    }
    String[] lines = observation.getStdout().strip().split("\\R");
    var counters = new LinkedHashMap<String, Long>();
    for (int i = 0; i < lines.length - 1; i++) {
      String[] entry = lines[i].strip().split("\\s+");
      counters.put(entry[0], Long.parseLong(entry[1]));
    }
    counters.put("memoryCurrentBytes", Long.parseLong(lines[lines.length - 1].strip()));
    if (!counters.containsKey("usage_usec")) {
      throw new IllegalStateException("Container CPU counter unavailable");
    }
    return counters;
  }

  private static Map<String, String> classHashes() throws Exception {
    var result = new LinkedHashMap<String, String>();
    for (Class<?> type :
        List.of(
            KafkaCapacityExperiment.class,
            AcceptOrderPaymentRequestService.class,
            OrderPaymentRequestedListener.class,
            QuarantineService.class,
            QuarantineRelay.class,
            com.commercesettle.payment.infrastructure.persistence.inbox.JdbcInboxStore.class,
            com.commercesettle.payment.infrastructure.persistence.inbox.JdbcQuarantineStore
                .class)) {
      try (InputStream in =
          type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
        if (in == null) {
          throw new IllegalStateException("Running class bytes unavailable");
        }
        result.put(type.getName(), hash(in.readAllBytes()));
      }
    }
    try (InputStream ddl = KafkaCapacityExperiment.class.getResourceAsStream("/db/payment.sql")) {
      if (ddl == null) {
        throw new IllegalStateException("Canonical experiment schema unavailable");
      }
      result.put("payment.sql", hash(ddl.readAllBytes()));
    }
    return result;
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void write(Path path, Map<String, Object> value) throws Exception {
    Files.writeString(path, JSON.writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface Condition {
    boolean test() throws Exception;
  }

  private static final class Measurements {
    private final long prefix;
    private final int capacity;
    private final AtomicLongArray starts;
    private final AtomicLongArray latencies;
    private final AtomicLongArray transactions;
    private final AtomicLongArray producerLatencies;
    private final AtomicLongArray scheduleLateness;
    private final AtomicIntegerArray receipts;
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger acked = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger durable = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();
    private final AtomicInteger lateSchedules = new AtomicInteger();

    private Measurements(long prefix, int capacity) {
      this.prefix = prefix;
      this.capacity = capacity;
      starts = new AtomicLongArray(capacity);
      latencies = new AtomicLongArray(capacity);
      transactions = new AtomicLongArray(capacity);
      producerLatencies = new AtomicLongArray(capacity);
      scheduleLateness = new AtomicLongArray(capacity);
      receipts = new AtomicIntegerArray(capacity);
    }

    private void record(UUID eventId, long before, long after) {
      long index = eventId.getLeastSignificantBits();
      if (eventId.getMostSignificantBits() != prefix || index < 0 || index >= capacity) {
        return;
      }
      int i = (int) index;
      if (receipts.incrementAndGet(i) == 1) {
        latencies.set(i, after - starts.get(i));
        transactions.set(i, after - before);
        durable.incrementAndGet();
      } else {
        duplicates.incrementAndGet();
      }
    }

    private long completedBefore(long deadline) {
      long count = 0;
      for (int i = 0; i < capacity; i++) {
        long latency = latencies.get(i);
        if (latency > 0 && starts.get(i) + latency <= deadline) {
          count++;
        }
      }
      return count;
    }
  }

  @TestConfiguration
  static class Instrumentation {
    @Bean
    @Primary
    OrderPaymentRequestAcceptor measuredAcceptor(AcceptOrderPaymentRequestService actual) {
      return (event, source) -> {
        long before = System.nanoTime();
        actual.accept(event, source);
        // actual은 @Transactional 프록시다. 반환 뒤이므로 실제 커밋을 지연에 포함한다.
        CURRENT.get().record(event.eventId(), before, System.nanoTime());
      };
    }
  }
}
