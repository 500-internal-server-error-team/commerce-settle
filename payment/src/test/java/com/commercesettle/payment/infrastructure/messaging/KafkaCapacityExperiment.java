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
    if (args.length != 4 || !("capacity".equals(args[2]) || "storage-probe".equals(args[2]))) {
      throw new IllegalArgumentException("Expected plan, output, mode and calibration paths");
    }
    Path planPath = Path.of(args[0]);
    Path output = Path.of(args[1]);
    if (Files.exists(output)) {
      throw new IllegalArgumentException("A previous experiment output must not be overwritten");
    }
    JsonNode plan = JSON.readTree(Files.readAllBytes(planPath));
    boolean storageProbe = "storage-probe".equals(args[2]);
    var result = new LinkedHashMap<String, Object>();
    result.put("mode", args[2]);
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
    try {
      long required = plan.path("storageBudget").path("calibrationStartBytes").asLong();
      if (!storageProbe) {
        JsonNode calibration = JSON.readTree(Files.readAllBytes(Path.of(args[3])));
        if (!"storage-probe-complete".equals(calibration.path("status").asText())
            || !result.get("planSha256").equals(calibration.path("planSha256").asText())
            || !JSON.valueToTree(result.get("runningClasses"))
                .equals(calibration.path("runningClasses"))) {
          throw new IllegalStateException(
              "Storage calibration must match this exact plan and classes");
        }
        var sizing = storageRequirement(plan, calibration.path("runs").get(0));
        result.put("storageSizing", sizing);
        result.put("storageCalibrationSha256", hash(Files.readAllBytes(Path.of(args[3]))));
        required = sizing.get("requiredStartBytes");
      }
      result.put("requiredStartFreeBytes", required);
      result.put("hostFreeBytesAtStart", requireDisk(required));
    } catch (Exception failure) {
      result.put("status", "preflight-failed");
      result.put("failureClass", failure.getClass().getName());
      write(output, result);
      throw failure;
    }
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
            .withEnv("KAFKA_LOG_DIRS", "/tmp/capacity-kafka-logs")
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
      requireDisk(((Number) result.get("requiredStartFreeBytes")).longValue());
      requireContainerDisk(postgres, ((Number) result.get("requiredStartFreeBytes")).longValue());
      if (storageProbe) {
        var readinessChecks = new ArrayList<Map<String, Object>>();
        result.put("topicReadinessChecks", readinessChecks);
        try (Admin admin = admin(kafka)) {
          for (int partitions : List.of(1, 3, 3)) {
            var check = new LinkedHashMap<String, Object>();
            check.put("partitions", partitions);
            readinessChecks.add(check);
            String group = "capacity-readiness-" + UUID.randomUUID();
            prepareTopic(admin, partitions, group, check);
            admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            check.put("passed", true);
            write(output, result);
          }
        }
        var probe = newTrial(runs, "storage-calibration");
        runTrial(
            plan,
            configuration(plan, "C"),
            postgres,
            kafka,
            1,
            -1,
            plan.path("storageBudget").path("calibrationSeconds").asInt(),
            false,
            probe);
        var sizing = storageRequirement(plan, JSON.valueToTree(probe));
        result.put("storageSizing", sizing);
        result.put("status", "storage-probe-complete");
        result.put("finishedAt", java.time.Instant.now().toString());
        write(output, result);
        return;
      }
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
    requireDisk(plan.path("storageBudget").path("reserveFreeBytes").asLong());
    run.put(
        "dockerDiskFreeBytesAtStart",
        requireContainerDisk(
            postgres, plan.path("storageBudget").path("reserveFreeBytes").asLong()));
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
      prepareTopic(admin, partitions, group, run);
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
      properties.put("app.dev-fixtures.enabled", "false");
      properties.put("app.dev-fixtures.run-scenarios", "false");
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
        long databaseBefore =
            jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
        String walStart = jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
        long brokerBefore = brokerAllocatedBytes(kafka);
        String dataDirectory = jdbc.queryForObject("SHOW data_directory", String.class);
        if (dataDirectory == null || !dataDirectory.startsWith("/var/lib/postgresql/")) {
          throw new IllegalStateException("Unexpected isolated PostgreSQL data directory");
        }
        long postgresBefore = allocatedBytes(postgres, dataDirectory);
        var walPeak = new AtomicLong(allocatedBytes(postgres, dataDirectory + "/pg_wal"));
        long walMaxSetting =
            jdbc.queryForObject(
                "SELECT pg_size_bytes(current_setting('max_wal_size'))", Long.class);
        long walBudget =
            Math.max(
                walPeak.get(),
                Math.multiplyExact(
                    walMaxSetting,
                    plan.path("storageBudget").path("walMaxSizeMultiplier").asLong()));
        run.put(
            "postgresWalSettings",
            jdbc.queryForMap(
                "SELECT current_setting('max_wal_size') AS max_wal_size,"
                    + "current_setting('min_wal_size') AS min_wal_size,current_setting('checkpoint_timeout') AS checkpoint_timeout,"
                    + "current_setting('full_page_writes') AS full_page_writes,current_setting('wal_compression') AS wal_compression"));
        var guardSamples = new ArrayList<Map<String, Long>>();
        run.put("storageGuards", guardSamples);
        java.util.concurrent.Callable<Map<String, Long>> storageGuard =
            () -> {
              var values =
                  checkStorage(
                      plan,
                      postgres,
                      kafka,
                      dataDirectory,
                      postgresBefore,
                      brokerBefore,
                      walBudget,
                      walPeak,
                      "storage-calibration".equals(run.get("phase")),
                      guardSamples);
              return values;
            };
        if ("storage-calibration".equals(run.get("phase"))) {
          requireDisk(plan.path("storageBudget").path("calibrationStartBytes").asLong());
          requireContainerDisk(
              postgres, plan.path("storageBudget").path("calibrationStartBytes").asLong());
        }
        var warmup = new Measurements(prefix, warmupCount);
        try {
          runWarmup(
              producer,
              warmup,
              rate,
              metrics.capacity,
              plan.path("maxInFlight").asInt(),
              plan.path("warmupSeconds").asInt(),
              storageGuard);
          producer.flush();
          if (!waitForDrain(
              admin, group, partitions, plan.path("maxDrainSeconds").asInt(), storageGuard)) {
            throw new IllegalStateException("Warmup did not drain within its fixed budget");
          }
        } finally {
          run.put(
              "warmup",
              Map.of(
                  "scheduled",
                  warmupCount,
                  "sent",
                  warmup.sent.get(),
                  "acked",
                  warmup.acked.get(),
                  "failures",
                  warmup.failed.get(),
                  "lateSchedules",
                  warmup.lateSchedules.get(),
                  "firstFailure",
                  java.util.Objects.toString(warmup.firstFailure.get(), "none"),
                  "scheduleLatenessMs",
                  percentiles(warmup.scheduleLateness),
                  "producerAckLatencyMs",
                  percentiles(warmup.producerLatencies),
                  "latencyPopulation",
                  "recorded sends; aborted first-failure index is reported separately"));
        }
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
            sample.put("storageGuard", storageGuard.call());
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
                "dockerDiskFreeBytes",
                requireContainerDisk(
                    postgres, plan.path("storageBudget").path("reserveFreeBytes").asLong()));
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
            sample.put(
                "hostDiskFreeBytes",
                requireDisk(plan.path("storageBudget").path("reserveFreeBytes").asLong()));
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
            waitForDrain(
                admin, group, partitions, plan.path("maxDrainSeconds").asInt(), storageGuard);
        listeners.stop();
        int expected = cohortSize(start, stopAt.get(), rate, metrics.capacity);
        long inbox = jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Long.class);
        run.put("inboxRowsAfterDrain", inbox);
        storageGuard.call();
        long brokerAfter = brokerAllocatedBytes(kafka);
        run.put(
            "storage",
            Map.of(
                "records",
                inbox,
                "serializedRecordBytes",
                body(prefix, 0).length,
                "databaseBeforeBytes",
                databaseBefore,
                "databaseAfterBytes",
                jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class),
                "inboxRelationAndIndexBytes",
                jdbc.queryForObject("SELECT pg_total_relation_size('payment.inbox')", Long.class),
                "walGeneratedBytes",
                jdbc.queryForObject(
                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)", Long.class, walStart),
                "walAllocatedPeakBytes",
                walPeak.get(),
                "walSpaceBudgetBytes",
                walBudget,
                "brokerBeforeAllocatedBytes",
                brokerBefore,
                "brokerAfterAllocatedBytes",
                brokerAfter));
        long finalGrowth =
            Math.max(0, allocatedBytes(postgres, dataDirectory) - postgresBefore)
                + Math.max(0, brokerAfter - brokerBefore);
        run.put("allocatedGrowthBytesAfterDrain", finalGrowth);
        if (walPeak.get() > walBudget
            || ("storage-calibration".equals(run.get("phase"))
                && finalGrowth
                    > plan.path("storageBudget").path("calibrationMaxGrowthBytes").asLong())) {
          throw new IllegalStateException("Storage budget exceeded during final drain");
        }
        requireDisk(plan.path("storageBudget").path("reserveFreeBytes").asLong());
        requireContainerDisk(
            postgres, plan.path("storageBudget").path("reserveFreeBytes").asLong());
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
        run.put(
            "firstProducerFailure", java.util.Objects.toString(metrics.firstFailure.get(), "none"));
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
      run.put(
          "firstProducerFailureAtExit",
          java.util.Objects.toString(metrics.firstFailure.get(), "none"));
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

  private static void runWarmup(
      KafkaProducer<String, byte[]> producer,
      Measurements metrics,
      int rate,
      int indexOffset,
      int maxInFlight,
      int seconds,
      java.util.concurrent.Callable<?> storageGuard)
      throws Exception {
    storageGuard.call();
    long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
    var inputClosed = new AtomicBoolean();
    var executor = Executors.newSingleThreadExecutor();
    var generator =
        executor.submit(
            () ->
                produce(
                    producer,
                    metrics,
                    rate,
                    indexOffset,
                    maxInFlight,
                    new AtomicLong(start + seconds * SECOND),
                    start,
                    inputClosed));
    try {
      long nextGuard = start + 5 * SECOND;
      while (!generator.isDone()) {
        parkUntil(nextGuard);
        storageGuard.call();
        nextGuard += 5 * SECOND;
      }
      generator.get(10, TimeUnit.SECONDS);
    } finally {
      if (!stopWorkers(executor, generator, null, inputClosed)) {
        throw new IllegalStateException("Warmup worker did not terminate within fixed budget");
      }
    }
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
        metrics.firstFailure.compareAndSet(null, "schedule-late:index=" + i);
        metrics.lateSchedules.incrementAndGet();
        metrics.failed.incrementAndGet();
        break;
      }
      if (!slots.tryAcquire()) {
        metrics.firstFailure.compareAndSet(null, "in-flight-limit:index=" + i);
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
                metrics.firstFailure.compareAndSet(null, "producer-callback:index=" + index);
                metrics.failed.incrementAndGet();
              }
              slots.release();
            });
        metrics.sent.incrementAndGet();
      } catch (RuntimeException failure) {
        metrics.firstFailure.compareAndSet(null, "producer-send:index=" + index);
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
    if (!hasDiskSpace(20L << 30, 8L << 30)
        || hasDiskSpace(2L << 30, 3L << 30)
        || !hasDiskSpace(8L << 30, 8L << 30)) {
      throw new IllegalStateException("Absolute disk budget contract failed");
    }
    return Map.of(
        "cohortCutoffs", true, "guardFailureStopsWorkers", true, "absoluteDiskBudget", true);
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

  private static void prepareTopic(
      Admin admin, int partitions, String group, Map<String, Object> run) throws Exception {
    if (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
      admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
      waitUntil(() -> !admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(TOPIC), 20);
    }
    var created = admin.createTopics(List.of(new NewTopic(TOPIC, partitions, (short) 1)));
    created.all().get(10, TimeUnit.SECONDS);
    String expectedTopicId = created.topicId(TOPIC).get(10, TimeUnit.SECONDS).toString();
    var initial = new HashMap<TopicPartition, OffsetAndMetadata>();
    for (int p = 0; p < partitions; p++) {
      initial.put(new TopicPartition(TOPIC, p), new OffsetAndMetadata(0));
    }
    initializeEmptyGroup(admin, group, expectedTopicId, initial, run);
  }

  private static void initializeEmptyGroup(
      Admin admin,
      String group,
      String expectedTopicId,
      Map<TopicPartition, OffsetAndMetadata> initial,
      Map<String, Object> run)
      throws Exception {
    long deadline = System.nanoTime() + 30 * SECOND;
    int attempts = 0;
    boolean writeAttempted = false;
    try {
      while (System.nanoTime() < deadline) {
        attempts++;
        try {
          var description =
              setupAwait(() -> admin.describeTopics(List.of(TOPIC)).allTopicNames(), deadline)
                  .get(TOPIC);
          if (!expectedTopicId.equals(description.topicId().toString())
              || description.partitions().size() != initial.size()
              || description.partitions().stream()
                  .anyMatch(p -> p.leader() == null || p.leader().id() < 0 || p.isr().isEmpty())) {
            parkUntil(Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
            continue;
          }
          var earliest = new HashMap<TopicPartition, OffsetSpec>();
          var latest = new HashMap<TopicPartition, OffsetSpec>();
          initial
              .keySet()
              .forEach(
                  partition -> {
                    earliest.put(partition, OffsetSpec.earliest());
                    latest.put(partition, OffsetSpec.latest());
                  });
          var starts = setupAwait(() -> admin.listOffsets(earliest).all(), deadline);
          var ends = setupAwait(() -> admin.listOffsets(latest).all(), deadline);
          if (starts.values().stream().anyMatch(offset -> offset.offset() != 0)
              || ends.values().stream().anyMatch(offset -> offset.offset() != 0)) {
            throw new IllegalStateException(
                "Fresh trial topic must have empty beginning and end offsets");
          }
          var before = setupOffsets(admin, group, deadline);
          if ((!writeAttempted && !before.isEmpty())
              || !initial.keySet().containsAll(before.keySet())
              || before.values().stream().anyMatch(offset -> offset.offset() != 0)) {
            throw new IllegalStateException("Fresh trial group contains unexpected offsets");
          }
          writeAttempted = true;
          setupAwait(() -> admin.alterConsumerGroupOffsets(group, initial).all(), deadline);
          var after = setupOffsets(admin, group, deadline);
          if (after.keySet().equals(initial.keySet())
              && after.values().stream().allMatch(offset -> offset.offset() == 0)) {
            run.put("topicId", expectedTopicId);
            return;
          }
          if (!initial.keySet().containsAll(after.keySet())
              || after.values().stream().anyMatch(offset -> offset.offset() != 0)) {
            throw new IllegalStateException(
                "Trial offset initialization returned unexpected values");
          }
        } catch (java.util.concurrent.ExecutionException failure) {
          Throwable cause = setupCause(failure);
          if (!(cause instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException)
              && !(cause instanceof org.apache.kafka.common.errors.LeaderNotAvailableException)
              && !(cause instanceof org.apache.kafka.common.errors.NotLeaderOrFollowerException)) {
            throw failure;
          }
        }
        parkUntil(Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
      }
      throw new java.util.concurrent.TimeoutException(
          "Fresh trial topic/group was not ready within30s");
    } finally {
      run.put("initialOffsetSetupAttempts", attempts);
    }
  }

  private static Map<TopicPartition, OffsetAndMetadata> setupOffsets(
      Admin admin, String group, long deadline) throws Exception {
    try {
      return setupAwait(
          () -> admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(), deadline);
    } catch (java.util.concurrent.ExecutionException failure) {
      if (setupCause(failure) instanceof org.apache.kafka.common.errors.GroupIdNotFoundException) {
        return Map.of();
      }
      throw failure;
    }
  }

  private static Throwable setupCause(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof java.util.concurrent.ExecutionException
            || cause instanceof java.util.concurrent.CompletionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static <T> T setupAwait(
      java.util.function.Supplier<org.apache.kafka.common.KafkaFuture<T>> request, long deadline)
      throws Exception {
    if (System.nanoTime() >= deadline) {
      throw new java.util.concurrent.TimeoutException(
          "Topic/group setup deadline elapsed before RPC");
    }
    var future = request.get();
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      future.cancel(true);
      throw new java.util.concurrent.TimeoutException("Topic/group setup deadline elapsed");
    }
    try {
      return future.get(Math.min(5 * SECOND, remaining), TimeUnit.NANOSECONDS);
    } catch (java.util.concurrent.TimeoutException failure) {
      future.cancel(true);
      throw failure;
    }
  }

  private static boolean waitForDrain(
      Admin admin,
      String group,
      int partitions,
      int seconds,
      java.util.concurrent.Callable<?> storageGuard)
      throws Exception {
    long deadline = System.nanoTime() + seconds * SECOND;
    long nextGuard = 0;
    do {
      if (System.nanoTime() >= nextGuard) {
        storageGuard.call();
        nextGuard = System.nanoTime() + 5 * SECOND;
      }
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

  private static boolean hasDiskSpace(long available, long required) {
    return required > 0 && available >= required;
  }

  private static long requireDisk(long required) throws Exception {
    var store = Files.getFileStore(Path.of("."));
    long available = store.getUsableSpace();
    if (!hasDiskSpace(available, required)) {
      throw new IllegalStateException("Preregistered absolute disk budget not available");
    }
    return available;
  }

  private static long requireContainerDisk(PostgreSQLContainer<?> postgres, long required)
      throws Exception {
    long available = containerDiskFreeBytes(postgres);
    if (!hasDiskSpace(available, required)) {
      throw new IllegalStateException("Preregistered absolute Docker disk budget not available");
    }
    return available;
  }

  private static long containerDiskFreeBytes(PostgreSQLContainer<?> postgres) throws Exception {
    var result = postgres.execInContainer("df", "-Pk", "/var/lib/postgresql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("Docker data disk margin could not be observed");
    }
    String[] lines = result.getStdout().strip().split("\\R");
    String[] columns = lines[lines.length - 1].strip().split("\\s+");
    return Math.multiplyExact(Long.parseLong(columns[3]), 1024);
  }

  private static Map<String, Long> checkStorage(
      JsonNode plan,
      PostgreSQLContainer<?> postgres,
      KafkaContainer kafka,
      String dataDirectory,
      long postgresBefore,
      long brokerBefore,
      long walBudget,
      AtomicLong walPeak,
      boolean calibration,
      List<Map<String, Long>> observations)
      throws Exception {
    var observation = new LinkedHashMap<String, Long>();
    observations.add(observation);
    observation.put("observedAtNanos", System.nanoTime());
    observation.put("walSpaceBudgetBytes", walBudget);
    long floor = plan.path("storageBudget").path("reserveFreeBytes").asLong();
    observation.put("reserveFreeBytes", floor);
    if (calibration) {
      observation.put(
          "calibrationMaxGrowthBytes",
          plan.path("storageBudget").path("calibrationMaxGrowthBytes").asLong());
    }
    long wal = allocatedBytes(postgres, dataDirectory + "/pg_wal");
    observation.put("walAllocatedBytes", wal);
    walPeak.accumulateAndGet(wal, Math::max);
    long growth =
        Math.max(0, allocatedBytes(postgres, dataDirectory) - postgresBefore)
            + Math.max(0, brokerAllocatedBytes(kafka) - brokerBefore);
    observation.put("dataAllocatedGrowthBytes", growth);
    long hostFree = Files.getFileStore(Path.of(".")).getUsableSpace();
    observation.put("hostFreeBytes", hostFree);
    long dockerFree = containerDiskFreeBytes(postgres);
    observation.put("dockerFreeBytes", dockerFree);
    if (wal > walBudget
        || (calibration
            && growth > plan.path("storageBudget").path("calibrationMaxGrowthBytes").asLong())
        || !hasDiskSpace(hostFree, floor)
        || !hasDiskSpace(dockerFree, floor)) {
      throw new IllegalStateException("Preregistered storage allocation budget exceeded");
    }
    return observation;
  }

  private static long brokerAllocatedBytes(KafkaContainer kafka) throws Exception {
    return allocatedBytes(kafka, "/tmp/capacity-kafka-logs");
  }

  private static long allocatedBytes(
      org.testcontainers.containers.GenericContainer<?> container, String directory)
      throws Exception {
    var observation = container.execInContainer("du", "-sk", directory);
    if (observation.getExitCode() != 0) {
      throw new IllegalStateException("Container allocated bytes unavailable");
    }
    return Math.multiplyExact(
        Long.parseLong(observation.getStdout().strip().split("\\s+")[0]), 1024);
  }

  private static Map<String, Long> storageRequirement(JsonNode plan, JsonNode probe) {
    JsonNode budget = plan.path("storageBudget");
    long expected =
        (plan.path("warmupSeconds").asLong() + budget.path("calibrationSeconds").asLong())
            * plan.path("offeredRecordsPerSecond").asLong();
    if (!probe.path("correctnessPassed").asBoolean()
        || !probe.path("generatorValid").asBoolean()
        || !probe.path("drained").asBoolean()
        || probe.path("inboxRowsAfterDrain").asLong() != expected) {
      throw new IllegalStateException("Storage probe must reconcile its entire planned cohort");
    }
    JsonNode storage = probe.path("storage");
    long rows = storage.path("records").asLong();
    long databaseGrowth =
        storage.path("databaseAfterBytes").asLong() - storage.path("databaseBeforeBytes").asLong();
    long walBudget = storage.path("walSpaceBudgetBytes").asLong();
    long brokerGrowth =
        Math.max(
            storage.path("brokerAfterAllocatedBytes").asLong()
                - storage.path("brokerBeforeAllocatedBytes").asLong(),
            rows * storage.path("serializedRecordBytes").asLong());
    if (rows != expected
        || rows <= 0
        || databaseGrowth <= 0
        || walBudget <= 0
        || storage.path("walAllocatedPeakBytes").asLong() > walBudget
        || brokerGrowth <= 0) {
      throw new IllegalStateException("Storage growth counters are incomplete");
    }
    long longestSeconds =
        Math.max(
            plan.path("confirmationSeconds").asLong(),
            15
                + plan.path("recoveryPauseSeconds").asLong()
                + plan.path("recoveryDrainSeconds").asLong()
                + 60);
    long peakRows =
        (longestSeconds + plan.path("warmupSeconds").asLong())
            * plan.path("offeredRecordsPerSecond").asLong();
    long variableBytes =
        Math.ceilDiv(
            Math.multiplyExact(
                Math.addExact(databaseGrowth, brokerGrowth),
                Math.multiplyExact(peakRows, budget.path("growthSafetyFactor").asLong())),
            rows);
    long required =
        Math.addExact(
            variableBytes,
            budget.path("reserveFreeBytes").asLong()
                + budget.path("fixedAndBuildAllowanceBytes").asLong()
                + walBudget
                + budget.path("brokerMetadataAllowanceBytes").asLong());
    return Map.of(
        "probeRecords",
        rows,
        "peakRecords",
        peakRows,
        "databaseGrowthBytes",
        databaseGrowth,
        "walSpaceBudgetBytes",
        walBudget,
        "brokerGrowthBytes",
        brokerGrowth,
        "projectedVariableBytes",
        variableBytes,
        "requiredStartBytes",
        required);
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
    private final AtomicReference<String> firstFailure = new AtomicReference<>();

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
