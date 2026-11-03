package com.commercesettle.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutRecipient;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL과 내부 호출 자격 판정을 거쳐 판매자 본인 조회 4개를 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current="
          + SellerSettlementControllerIntegrationTest.GATEWAY,
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SellerSettlementControllerIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final Instant T1 = Instant.parse("2026-10-20T01:00:00Z");
  private static final Instant T2 = T1.plusSeconds(60);
  private static final Instant T3 = T2.plusSeconds(60);
  private static final Instant T4 = T3.plusSeconds(60);
  private static final long BASE = 10_000;
  private static final long FEE = BASE * 15 / 100;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SettlementItemRepository items;
  @Autowired SettlementBatchRepository batches;
  @Autowired PayoutRepository payouts;
  @Autowired PayoutJobRepository payoutJobs;
  @Autowired PayoutWorkerSettings workerSettings;
  @Autowired ObjectMapper mapper;

  private final AtomicInteger payoutDays = new AtomicInteger();
  private UUID seller;
  private UUID otherSeller;

  @BeforeEach
  void setUp() {
    seller = UUID.randomUUID();
    otherSeller = UUID.randomUUID();
  }

  @AfterEach
  void deleteRows() {
    for (String table :
        List.of("settlement_items", "settlement_batches", "payouts", "payout_jobs")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @Test
  @DisplayName("정산 항목은 본인 것만 최신순으로 페이지를 나눠 주고, 시각은 KST로 낸다")
  void listsOwnItemsNewestFirst() throws Exception {
    SettlementItem first = item(seller, T1);
    SettlementItem second = item(seller, T2);
    SettlementItem third = item(seller, T3);
    item(otherSeller, T4);

    mvc.perform(asSeller(get("/api/sellers/me/settlement-items").param("size", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id").value(contains(id(third), id(second))))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.items[0].status").value("WAITING_CONFIRMATION"))
        .andExpect(jsonPath("$.items[0].settlementBaseAmount").value(BASE))
        .andExpect(jsonPath("$.items[0].feeAmount").value(FEE))
        .andExpect(jsonPath("$.items[0].netAmount").value(BASE - FEE))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-20T10:02:00+09:00"))
        .andExpect(jsonPath("$.items[0].readyAt").value(nullValue()))
        .andExpect(jsonPath("$.items[0].payoutId").value(nullValue()));

    mvc.perform(
            asSeller(get("/api/sellers/me/settlement-items").param("page", "1").param("size", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id").value(contains(id(first))));
  }

  @Test
  @DisplayName("정산 배치는 본인 것만 최신순으로 준다")
  void listsOwnBatchesNewestFirst() throws Exception {
    SettlementBatch older = batch(seller, LocalDate.of(2026, 10, 18), T1);
    SettlementBatch newer = batch(seller, LocalDate.of(2026, 10, 19), T2);
    batch(otherSeller, LocalDate.of(2026, 10, 19), T3);

    mvc.perform(asSeller(get("/api/sellers/me/settlement-batches")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id").value(contains(id(newer), id(older))))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].batchDate").value("2026-10-19"))
        .andExpect(jsonPath("$.items[0].cutoffAt").value("2026-10-19T10:00:00+09:00"))
        .andExpect(jsonPath("$.items[0].status").value("RESERVED"))
        .andExpect(jsonPath("$.items[0].policyVersion").value("delivery-daily-v1"));
  }

  @Test
  @DisplayName("지급은 대기 중일 때만 다음 시도를 알리고, 성공·확정 거절·운영 확인 대기는 다음 시도가 없다")
  void payoutHoldReasonAndNextAttempt() throws Exception {
    Payout pending = payout(seller, T1);
    Payout unknown = payout(seller, T2);
    Payout succeeded = payout(seller, T3);
    Payout declined = payout(seller, T4);
    payout(otherSeller, T4);
    jdbc.update(
        "UPDATE settlement.payout_jobs SET next_run_at = ? WHERE payout_id = ?",
        Timestamp.from(T1.plusSeconds(5)),
        pending.getId());
    update(unknown, "UNKNOWN", "UNKNOWN", null, "RETRY", T2.plusSeconds(300));
    update(succeeded, "SUCCEEDED", null, T3.plusSeconds(1), "DONE", T3);
    update(declined, "DECLINED", "CONFIRMED_REJECTION", null, "REVIEW_REQUIRED", T4);

    mvc.perform(asSeller(get("/api/sellers/me/payouts")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].id")
                .value(contains(id(declined), id(succeeded), id(unknown), id(pending))))
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.items[0].holdReason").value("CONFIRMED_REJECTION"))
        .andExpect(jsonPath("$.items[0].nextAttemptAt").value(nullValue()))
        .andExpect(jsonPath("$.items[1].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.items[1].holdReason").value(nullValue()))
        .andExpect(jsonPath("$.items[1].nextAttemptAt").value(nullValue()))
        .andExpect(jsonPath("$.items[1].succeededAt").value("2026-10-20T10:02:01+09:00"))
        .andExpect(jsonPath("$.items[2].holdReason").value("UNKNOWN"))
        .andExpect(jsonPath("$.items[2].nextAttemptAt").value("2026-10-20T10:06:00+09:00"))
        .andExpect(jsonPath("$.items[3].status").value("PENDING"))
        .andExpect(jsonPath("$.items[3].holdReason").value(nullValue()))
        .andExpect(jsonPath("$.items[3].nextAttemptAt").value("2026-10-20T10:00:05+09:00"))
        .andExpect(jsonPath("$.items[3].recipientAccountRef").value("acct-" + seller));
  }

  @Test
  @DisplayName("임대가 만료된 실행 중 작업은 시도가 남았을 때만 회수 시각을 다음 시도로 알린다")
  void expiredLeaseIsNextAttemptOnlyWhileAttemptsRemain() throws Exception {
    Payout expired = payout(seller, T1);
    Payout exhausted = payout(seller, T2);
    Payout active = payout(seller, T3);
    // Worker가 중단돼 임대만 남은 작업은 다음 임대 때 회수돼 다시 실행된다.
    running(expired, 1, "TIMESTAMPTZ '2026-10-20T01:00:30Z'");
    // 시도 상한에 닿은 작업은 회수되면 호출 없이 운영 확인으로 넘어간다.
    running(exhausted, workerSettings.maxAttempts(), "TIMESTAMPTZ '2026-10-20T01:01:30Z'");
    // 임대가 유효하면 지금 실행 중이라 다음 시도가 없다.
    running(active, 1, "now() + interval '1 minute'");

    mvc.perform(asSeller(get("/api/sellers/me/payouts")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].id").value(contains(id(active), id(exhausted), id(expired))))
        .andExpect(jsonPath("$.items[0].nextAttemptAt").value(nullValue()))
        .andExpect(jsonPath("$.items[1].nextAttemptAt").value(nullValue()))
        .andExpect(jsonPath("$.items[2].nextAttemptAt").value("2026-10-20T10:00:30+09:00"));
    mvc.perform(asSeller(get("/api/sellers/me/payouts/{id}", expired.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextAttemptAt").value("2026-10-20T10:00:30+09:00"));
  }

  @Test
  @DisplayName("Gateway가 보여 주는 런타임 명세의 응답 코드는 docs/openapi.json과 같다")
  void runtimeResponsesMatchContract() throws Exception {
    JsonNode runtime =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode contract;
    try (InputStream in = new ClassPathResource("contracts/openapi.json").getInputStream()) {
      contract = mapper.readTree(in);
    }
    for (String path :
        List.of(
            "/api/sellers/me/settlement-items",
            "/api/sellers/me/settlement-batches",
            "/api/sellers/me/payouts",
            "/api/sellers/me/payouts/{payoutId}")) {
      assertThat(responseCodes(runtime, path)).as(path).isEqualTo(responseCodes(contract, path));
    }
  }

  @Test
  @DisplayName("지급 단건은 본인 것만 주고, 다른 판매자의 지급과 없는 지급은 똑같이 404다")
  void payoutDetailIsOwnerOnly() throws Exception {
    Payout own = payout(seller, T1);
    Payout others = payout(otherSeller, T2);

    mvc.perform(asSeller(get("/api/sellers/me/payouts/{id}", own.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id(own)))
        .andExpect(jsonPath("$.batchId").value(own.getBatchId().toString()))
        .andExpect(jsonPath("$.amount").value(BASE - FEE));
    for (UUID missing : List.of(others.getId(), UUID.randomUUID())) {
      mvc.perform(asSeller(get("/api/sellers/me/payouts/{id}", missing)))
          .andExpect(status().isNotFound())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));
    }
  }

  @Test
  @DisplayName("신원 헤더가 없으면 401, 구매자는 403, 페이지 범위 오류는 400이다")
  void rejectsMissingIdentityWrongRoleAndBadPage() throws Exception {
    mvc.perform(
            get("/api/sellers/me/payouts").header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"));
    mvc.perform(
            get("/api/sellers/me/payouts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY)
                .header(AuthenticatedSellerResolver.ACTOR_KIND, "BUYER")
                .header(AuthenticatedSellerResolver.ACTOR_ID, UUID.randomUUID().toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    mvc.perform(asSeller(get("/api/sellers/me/settlement-items").param("size", "101")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("size"));
    mvc.perform(asSeller(get("/api/sellers/me/payouts/{id}", "not-a-uuid")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
  }

  private MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder request) {
    return request
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY)
        .header(AuthenticatedSellerResolver.ACTOR_KIND, "SELLER")
        .header(AuthenticatedSellerResolver.ACTOR_ID, seller.toString());
  }

  private SettlementItem item(UUID sellerId, Instant createdAt) {
    return items.save(
        SettlementItem.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            sellerId,
            new SettlementAmounts(BASE, 0, BASE, BASE, 0, FEE, BASE - FEE),
            0,
            createdAt));
  }

  private SettlementBatch batch(UUID sellerId, LocalDate batchDate, Instant createdAt) {
    return batches.save(
        SettlementBatch.reserve(sellerId, batchDate, "delivery-daily-v1", BASE - FEE, createdAt));
  }

  private Payout payout(UUID sellerId, Instant createdAt) {
    SettlementBatch batch =
        batches.save(
            SettlementBatch.reserve(
                sellerId,
                LocalDate.of(2026, 9, 1).plusDays(payoutDays.getAndIncrement()),
                "delivery-daily-v1",
                BASE - FEE,
                createdAt));
    Payout payout =
        payouts.save(
            Payout.create(
                UUID.randomUUID(),
                batch.getId(),
                sellerId,
                new PayoutRecipient("acct-" + sellerId, 1),
                "funding-account",
                "payout-v1",
                BASE - FEE,
                createdAt));
    payoutJobs.save(PayoutJob.schedule(payout, createdAt));
    return payout;
  }

  private void update(
      Payout payout,
      String status,
      String holdReason,
      Instant succeededAt,
      String jobStatus,
      Instant nextRunAt) {
    jdbc.update(
        "UPDATE settlement.payouts SET status = ?, hold_reason = ?, succeeded_at = ? WHERE id = ?",
        status,
        holdReason,
        succeededAt == null ? null : Timestamp.from(succeededAt),
        payout.getId());
    jdbc.update(
        "UPDATE settlement.payout_jobs SET status = ?, next_run_at = ? WHERE payout_id = ?",
        jobStatus,
        Timestamp.from(nextRunAt),
        payout.getId());
  }

  private void running(Payout payout, int attempts, String leaseUntilSql) {
    jdbc.update(
        "UPDATE settlement.payout_jobs SET status = 'RUNNING', attempts = ?, lease_owner = 'w',"
            + " lease_token = gen_random_uuid(), lease_until = "
            + leaseUntilSql
            + " WHERE payout_id = ?",
        attempts,
        payout.getId());
  }

  private static Set<String> responseCodes(JsonNode document, String path) {
    Set<String> codes = new TreeSet<>();
    document
        .path("paths")
        .path(path)
        .path("get")
        .path("responses")
        .fieldNames()
        .forEachRemaining(codes::add);
    assertThat(codes).as(path).isNotEmpty();
    return codes;
  }

  private static String id(SettlementItem item) {
    return item.getId().toString();
  }

  private static String id(SettlementBatch batch) {
    return batch.getId().toString();
  }

  private static String id(Payout payout) {
    return payout.getId().toString();
  }
}
