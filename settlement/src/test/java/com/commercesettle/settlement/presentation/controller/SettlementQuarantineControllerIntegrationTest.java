package com.commercesettle.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.settlement.application.port.QuarantineStore.Delivery;
import com.commercesettle.settlement.application.port.QuarantineStore.Source;
import com.commercesettle.settlement.application.service.QuarantineService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL과 운영자 자격 판정을 거쳐 격리 원문 조회·원키 재처리·소진 발행 재개를 확인한다. Kafka 발행 자체는 격리 통합 시험이 맡는다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current="
          + SettlementQuarantineControllerIntegrationTest.GATEWAY,
      "app.internal-auth.senders[operator\\:ops].current="
          + SettlementQuarantineControllerIntegrationTest.OPERATOR,
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class SettlementQuarantineControllerIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String OPERATOR = "test-operator-credential-0123456789abcdef0123";
  private static final String SOURCE_TOPIC = "commerce-settle.payment.v1";
  private static final long LEASE_MS = 60_000;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired QuarantineService quarantines;

  @AfterEach
  void deleteRows() {
    for (String table :
        List.of(
            "inbox_quarantine_resumes",
            "inbox_quarantine_deliveries",
            "inbox_quarantines",
            "idempotency_records",
            "audit_logs")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @Test
  @DisplayName("운영자는 원문 없이 위치·크기·해시와 작업을 최신순으로 보고, 다른 자격은 거절된다")
  void listsQuarantinesWithoutRawBytes() throws Exception {
    UUID older = capture(1, "key", "secret-body");
    jdbc.update(
        "UPDATE settlement.inbox_quarantines SET created_at = created_at - interval '1 minute'"
            + " WHERE id = ?",
        older);
    UUID newer = capture(2, null, null);

    mvc.perform(asOperator(get("/internal/settlement-quarantines")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id").value(contains(newer.toString(), older.toString())))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[1].sourceTopic").value(SOURCE_TOPIC))
        .andExpect(jsonPath("$.items[1].sourceOffset").value(1))
        .andExpect(jsonPath("$.items[1].errorCode").value("SETTLEMENT_EVENT_INVALID"))
        .andExpect(jsonPath("$.items[1].bodySize").value(11))
        .andExpect(jsonPath("$.items[1].bodySha256").value(sha256("secret-body")))
        .andExpect(jsonPath("$.items[1]", not(hasKey("rawBody"))))
        .andExpect(jsonPath("$.items[1]", not(hasKey("messageKey"))))
        .andExpect(jsonPath("$.items[1].deliveries[0].kind").value("DLT"))
        .andExpect(jsonPath("$.items[1].deliveries[0].operatorId").value(nullValue()))
        .andExpect(jsonPath("$.items[0].bodySize").value(nullValue()))
        .andExpect(jsonPath("$.items[0].bodySha256").value(nullValue()));
    String listed =
        mvc.perform(asOperator(get("/internal/settlement-quarantines")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(listed).doesNotContain("secret-body");

    jdbc.update(
        "UPDATE settlement.inbox_quarantine_deliveries SET status = 'PUBLISHED'"
            + " WHERE quarantine_id = ?",
        older);
    mvc.perform(asOperator(get("/internal/settlement-quarantines?deliveryStatus=PUBLISHED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id").value(contains(older.toString())))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(asOperator(get("/internal/settlement-quarantines?deliveryStatus=DONE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    mvc.perform(asOperator(get("/internal/settlement-quarantines/" + older)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(older.toString()))
        .andExpect(jsonPath("$.version").value(0));
    mvc.perform(asOperator(get("/internal/settlement-quarantines/" + UUID.randomUUID())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));

    mvc.perform(
            get("/internal/settlement-quarantines")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY))
        .andExpect(status().isForbidden());
    mvc.perform(get("/internal/settlement-quarantines")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/internal/settlement-quarantines/" + older + "/replays")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY)
                .header("Idempotency-Key", "replay-key-0000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "PARSER_FIXED")))
        .andExpect(status().isForbidden());
    assertThat(count("inbox_quarantine_deliveries")).isEqualTo(2);
  }

  @Test
  @DisplayName("원키 재처리는 원래 토픽·키·bytes로 한 번만 접수하고, 같은 키 재요청은 처음 결과를 돌려준다")
  void replaysOnceWithOriginalBytes() throws Exception {
    UUID quarantine = capture(3, "key", "body");
    String body = body(0, "PARSER_FIXED");

    replay(quarantine, "replay-key-0001", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.deliveries[1].kind").value("REPLAY"))
        .andExpect(jsonPath("$.deliveries[1].topic").value(SOURCE_TOPIC))
        .andExpect(jsonPath("$.deliveries[1].status").value("PENDING"))
        .andExpect(jsonPath("$.deliveries[1].operatorId").value("operator:ops"))
        .andExpect(jsonPath("$.deliveries[1].reasonCode").value("PARSER_FIXED"));

    replay(quarantine, "replay-key-0001", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.version").value(1));
    assertThat(replayCount(quarantine)).isEqualTo(1);
    Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM settlement.audit_logs");
    assertThat(audit)
        .containsEntry("actor_kind", "OPERATOR")
        .containsEntry("actor_ref", "operator:ops")
        .containsEntry("action", "QUARANTINE_REPLAY_REQUESTED")
        .containsEntry("reason_code", "PARSER_FIXED")
        .containsEntry("correlation_id", quarantine.toString());

    replay(quarantine, "replay-key-0001", body(0, "OTHER_REASON"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));
    replay(quarantine, "replay-key-0002", body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
    replay(quarantine, "replay-key-0003", body(1, "PARSER_FIXED"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QUARANTINE_REPLAY_ACTIVE"));
    replay(quarantine, "replay-key-0004", body(1, "parser fixed"))
        .andExpect(status().isBadRequest());
    replay(UUID.randomUUID(), "replay-key-0005", body).andExpect(status().isNotFound());
    assertThat(replayCount(quarantine)).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);

    Delivery claimed =
        quarantines.claim(10, LEASE_MS, 10).stream()
            .filter(delivery -> delivery.topic().equals(SOURCE_TOPIC))
            .findFirst()
            .orElseThrow();
    assertThat(claimed.key()).isEqualTo("key");
    assertThat(claimed.body()).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("같은 버전의 동시 재처리는 하나만 접수한다")
  void concurrentReplayAcceptsOne() throws Exception {
    UUID quarantine = capture(4, "key", "body");
    String body = body(0, "PARSER_FIXED");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (String key : List.of("replay-key-0101", "replay-key-0102")) {
        Callable<Integer> call =
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return replay(quarantine, key, body).andReturn().getResponse().getStatus();
            };
        results.add(pool.submit(call));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get(30, TimeUnit.SECONDS));
      }
      assertThat(statuses).containsExactlyInAnyOrder(202, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(replayCount(quarantine)).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
  }

  @Test
  @DisplayName("시도를 다 쓴 발행 작업만 같은 작업으로 재개하고, 이전 시도 수를 이력에 한 번 남긴다")
  void resumesExhaustedDeliveryOnce() throws Exception {
    UUID quarantine = capture(5, "key", "body");
    UUID delivery =
        jdbc.queryForObject(
            "SELECT id FROM settlement.inbox_quarantine_deliveries WHERE quarantine_id = ?",
            UUID.class,
            quarantine);
    resume(delivery, "resume-key-0001", body(0, "DLT_RECOVERED"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QUARANTINE_DELIVERY_NOT_RESUMABLE"));
    jdbc.update(
        "UPDATE settlement.inbox_quarantine_deliveries"
            + " SET status = 'REVIEW_REQUIRED', attempts = 10, version = 7 WHERE id = ?",
        delivery);
    String body = body(7, "DLT_RECOVERED");

    resume(delivery, "resume-key-0002", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(delivery.toString()))
        .andExpect(jsonPath("$.quarantineId").value(quarantine.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.attempts").value(0))
        .andExpect(jsonPath("$.version").value(8))
        .andExpect(jsonPath("$.resumes[0].previousAttempts").value(10))
        .andExpect(jsonPath("$.resumes[0].expectedVersion").value(7))
        .andExpect(jsonPath("$.resumes[0].operatorId").value("operator:ops"));

    resume(delivery, "resume-key-0002", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.version").value(8));
    resume(delivery, "resume-key-0003", body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
    resume(UUID.randomUUID(), "resume-key-0004", body).andExpect(status().isNotFound());
    assertThat(count("inbox_quarantine_resumes")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT action FROM settlement.audit_logs WHERE aggregate_id = ?",
                String.class,
                delivery))
        .isEqualTo("QUARANTINE_DELIVERY_RESUMED");
    assertThat(count("audit_logs")).isEqualTo(1);
  }

  private UUID capture(long offset, String key, String body) {
    return quarantines.capture(
        new Source(
            "settlement.v1",
            SOURCE_TOPIC,
            0,
            offset,
            key,
            body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
  }

  private ResultActions replay(UUID quarantine, String key, String body) throws Exception {
    return mvc.perform(
        asOperator(post("/internal/settlement-quarantines/" + quarantine + "/replays"))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions resume(UUID delivery, String key, String body) throws Exception {
    return mvc.perform(
        asOperator(post("/internal/settlement-quarantine-deliveries/" + delivery + "/resumes"))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static MockHttpServletRequestBuilder asOperator(MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR);
  }

  private static String body(long expectedVersion, String reasonCode) {
    return "{\"expectedVersion\":%d,\"reasonCode\":\"%s\"}".formatted(expectedVersion, reasonCode);
  }

  private long replayCount(UUID quarantine) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement.inbox_quarantine_deliveries"
            + " WHERE quarantine_id = ? AND kind = 'REPLAY'",
        Long.class,
        quarantine);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
