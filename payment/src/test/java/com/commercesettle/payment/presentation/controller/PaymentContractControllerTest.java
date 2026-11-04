package com.commercesettle.payment.presentation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.internalauth.InternalAuthAutoConfiguration;
import com.commercesettle.payment.application.dto.MileageEntryView;
import com.commercesettle.payment.application.dto.MileageWalletView;
import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.application.dto.PaymentAttemptView;
import com.commercesettle.payment.application.dto.PaymentJobView;
import com.commercesettle.payment.application.dto.PaymentView;
import com.commercesettle.payment.application.dto.ReconciliationEventPageView;
import com.commercesettle.payment.application.dto.SettlementContextView;
import com.commercesettle.payment.application.port.ReconciliationEventReader.ReconciliationEvent;
import com.commercesettle.payment.application.service.MileageQueryService;
import com.commercesettle.payment.application.service.PaymentAttemptQueryService;
import com.commercesettle.payment.application.service.PaymentJobOperationService;
import com.commercesettle.payment.application.service.PaymentQueryService;
import com.commercesettle.payment.application.service.ReconciliationQueryService;
import com.commercesettle.payment.domain.exception.IdempotencyConflictException;
import com.commercesettle.payment.domain.exception.InvalidReconciliationCursorException;
import com.commercesettle.payment.domain.exception.PaymentStateConflictException;
import com.commercesettle.payment.domain.exception.ReconciliationCutoffNotReadyException;
import com.commercesettle.payment.domain.model.MileageEntryKind;
import com.commercesettle.payment.domain.model.PaymentAttemptStatus;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.model.PaymentStatus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 새 조회·운영 API의 응답 형식, 오류 코드, 명세 x-actor에 따른 발신자 판정을 HTTP 경계에서 확인한다. */
@WebMvcTest(
    controllers = {
      MileageController.class,
      PaymentController.class,
      SettlementContractController.class,
      PaymentIntegrityController.class,
      PaymentJobController.class
    },
    properties = {
      "app.internal-auth.service=payment",
      "app.internal-auth.senders.gateway.current=" + PaymentContractControllerTest.GATEWAY,
      "app.internal-auth.senders[service\\:settlement].current="
          + PaymentContractControllerTest.SETTLEMENT,
      "app.internal-auth.senders[service\\:settlement].previous="
          + PaymentContractControllerTest.SETTLEMENT_PREVIOUS,
      "app.internal-auth.senders[operator\\:ops-1].current="
          + PaymentContractControllerTest.OPERATOR
    })
@Import(InternalAuthAutoConfiguration.class)
class PaymentContractControllerTest {
  static final String GATEWAY = "gateway-credential-0123456789abcdef0123456789";
  static final String SETTLEMENT = "settlement-credential-0123456789abcdef012345";
  static final String SETTLEMENT_PREVIOUS = "settlement-previous-credential-0123456789abcdef012345";
  static final String OPERATOR = "operator-credential-0123456789abcdef01234567";
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final Instant AT = Instant.parse("2026-11-02T01:00:00Z");
  private static final String CUTOFF = "2026-11-02T00:00:00+09:00";

  @Autowired MockMvc mvc;
  @MockitoBean MileageQueryService mileage;
  @MockitoBean PaymentQueryService payments;
  @MockitoBean PaymentAttemptQueryService attempts;
  @MockitoBean ReconciliationQueryService reconciliation;
  @MockitoBean PaymentJobOperationService jobs;
  @MockitoBean com.commercesettle.payment.application.service.PaymentIntegrityService integrity;

  @Test
  @DisplayName("대조 마지막 페이지의 null cursor와 준비 전 마감 409를 HTTP 계약으로 보존한다")
  void preservesIntegrityPageAndCutoffConflict() throws Exception {
    var page = new java.util.LinkedHashMap<String, Object>();
    page.put("items", List.of());
    page.put("page", 0);
    page.put("size", 100);
    page.put("totalElements", 0L);
    page.put("totalPages", 0L);
    page.put("cutoffAt", AT.atOffset(java.time.ZoneOffset.ofHours(9)));
    page.put("snapshotAt", AT.plusSeconds(600).atOffset(java.time.ZoneOffset.ofHours(9)));
    page.put("nextCursor", null);
    page.put("direction", "PAYMENT");
    given(integrity.read(any(), any(), any(), anyInt(), any())).willReturn(page);
    var request =
        get("/internal/payment-reconciliation-checks")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT_PREVIOUS)
            .param("cutoffAt", CUTOFF);
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").value(nullValue()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("\"nextCursor\":null")));
    given(integrity.read(any(), any(), any(), anyInt(), any()))
        .willThrow(new ReconciliationCutoffNotReadyException());
    mvc.perform(request)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RECONCILIATION_CUTOFF_NOT_READY"));
  }

  @Test
  @DisplayName("구매자는 Gateway를 거쳐 본인 마일리지 잔액·원장과 승인 시도를 조회한다")
  void buyerReadsMileageAndAttempts() throws Exception {
    given(mileage.wallet(BUYER)).willReturn(new MileageWalletView(5_000, 2_000, 3_000, 4));
    given(mileage.entries(BUYER, 0, 20))
        .willReturn(
            new PageView<>(
                List.of(
                    new MileageEntryView(
                        UUID.randomUUID(), null, MileageEntryKind.SEED, 5_000, 5_000, AT)),
                0,
                20,
                1));
    given(attempts.list(BUYER, PAYMENT, 0, 20))
        .willReturn(
            new PageView<>(
                List.of(
                    new PaymentAttemptView(
                        UUID.randomUUID(),
                        PAYMENT,
                        1,
                        "PG_A",
                        "CREDIT_CARD",
                        8_000,
                        PaymentAttemptStatus.UNKNOWN,
                        AT)),
                0,
                20,
                1));

    mvc.perform(asBuyer(get("/api/buyers/me/mileage")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(5_000))
        .andExpect(jsonPath("$.reservedAmount").value(2_000))
        .andExpect(jsonPath("$.availableAmount").value(3_000))
        .andExpect(jsonPath("$.version").value(4));
    mvc.perform(asBuyer(get("/api/buyers/me/mileage/entries")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].kind").value("SEED"))
        .andExpect(jsonPath("$.items[0].paymentId").value(nullValue()))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-02T10:00:00+09:00"))
        .andExpect(jsonPath("$.totalPages").value(1));
    mvc.perform(asBuyer(get("/api/payments/{id}/attempts", PAYMENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].providerId").value("PG_A"))
        .andExpect(jsonPath("$.items[0].status").value("UNKNOWN"))
        .andExpect(jsonPath("$.items[0].accountRef").doesNotExist());
  }

  @Test
  @DisplayName("마일리지 원장의 잘못된 page·size는 400이다")
  void rejectsInvalidMileagePage() throws Exception {
    mvc.perform(asBuyer(get("/api/buyers/me/mileage/entries?size=101")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("size"));
    verifyNoInteractions(mileage);
  }

  @Test
  @DisplayName("Settlement 자격은 정산 컨텍스트를 읽고, Gateway 자격은 403이다")
  void onlySettlementReadsSettlementContext() throws Exception {
    PaymentView payment =
        new PaymentView(
            PAYMENT,
            UUID.randomUUID(),
            PaymentStatus.CONFIRMED,
            10_000,
            0,
            10_000,
            "CREDIT_CARD",
            null,
            AT,
            2,
            AT,
            0,
            10_000);
    UUID seller = UUID.randomUUID();
    given(payments.settlementContext(PAYMENT))
        .willReturn(
            new SettlementContextView(payment, 1_500, 8_500, seller, UUID.randomUUID(), "v1"));

    mvc.perform(as(SETTLEMENT, get("/internal/payments/{id}/settlement-context", PAYMENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payment.id").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.payment.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.feeAmount").value(1_500))
        .andExpect(jsonPath("$.sellerNetAmount").value(8_500))
        .andExpect(jsonPath("$.sellerId").value(seller.toString()))
        .andExpect(jsonPath("$.policyVersion").value("v1"));
    mvc.perform(as(GATEWAY, get("/internal/payments/{id}/settlement-context", PAYMENT)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    mvc.perform(as(OPERATOR, get("/internal/payments/{id}/settlement-context", PAYMENT)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("확정 전 결제의 정산 컨텍스트는 409 PAYMENT_STATE_CONFLICT다")
  void rejectsUnconfirmedSettlementContext() throws Exception {
    given(payments.settlementContext(PAYMENT))
        .willThrow(new PaymentStateConflictException("확정된 결제만 정산 컨텍스트를 제공합니다."));

    mvc.perform(as(SETTLEMENT, get("/internal/payments/{id}/settlement-context", PAYMENT)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
  }

  @Test
  @DisplayName("대조 조회는 발행 위치 미확정을 null 위치로 그대로 담고, 마감 준비 전·잘못된 cursor는 409·400이다")
  void readsReconciliationEvents() throws Exception {
    ReconciliationEvent pending =
        new ReconciliationEvent(
            UUID.randomUUID(),
            "PaymentConfirmed",
            AT,
            AT,
            PAYMENT,
            null,
            9_000,
            1_000,
            0,
            10_000,
            1_500,
            8_500,
            false,
            "commerce-settle.payment.v1",
            null,
            null,
            null);
    given(reconciliation.read(Instant.parse("2026-11-01T15:00:00Z"), null, 100))
        .willReturn(
            new ReconciliationEventPageView(
                List.of(pending), 0, 100, 1, Instant.parse("2026-11-01T15:00:00Z"), AT, null));

    mvc.perform(
            as(
                SETTLEMENT,
                get("/internal/payment-reconciliation-events").param("cutoffAt", CUTOFF)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].publicationState").value("PENDING"))
        .andExpect(jsonPath("$.items[0].refundId").value(nullValue()))
        .andExpect(jsonPath("$.items[0].partition").value(nullValue()))
        .andExpect(jsonPath("$.items[0].offset").value(nullValue()))
        .andExpect(jsonPath("$.items[0].publishedAt").value(nullValue()))
        .andExpect(jsonPath("$.cutoffAt").value(CUTOFF))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()))
        .andExpect(jsonPath("$.totalPages").value(1));

    willThrow(new ReconciliationCutoffNotReadyException())
        .given(reconciliation)
        .read(any(), any(), anyInt());
    mvc.perform(
            as(
                SETTLEMENT,
                get("/internal/payment-reconciliation-events").param("cutoffAt", CUTOFF)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RECONCILIATION_CUTOFF_NOT_READY"));

    willThrow(new InvalidReconciliationCursorException())
        .given(reconciliation)
        .read(any(), any(), anyInt());
    mvc.perform(
            as(
                SETTLEMENT,
                get("/internal/payment-reconciliation-events")
                    .param("cutoffAt", CUTOFF)
                    .param("cursor", "abc")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cursor"));

    mvc.perform(as(SETTLEMENT, get("/internal/payment-reconciliation-events")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cutoffAt"));
    mvc.perform(
            as(GATEWAY, get("/internal/payment-reconciliation-events").param("cutoffAt", CUTOFF)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("운영자는 작업을 조회하고 확인된 운영자 ID·멱등 키로 재처리를 접수한다")
  void operatorReplaysJob() throws Exception {
    PaymentJobView job =
        new PaymentJobView(JOB, PAYMENT, "APPROVE", PaymentJobStatus.PENDING, 0, AT, null, 5, AT);
    given(jobs.list(0, 20)).willReturn(new PageView<>(List.of(job), 0, 20, 1));
    given(jobs.replay("operator:ops-1", JOB, "replay-key-0001", 4, "PG_RECOVERED")).willReturn(job);

    mvc.perform(as(OPERATOR, get("/internal/payment-jobs")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].resourceId").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.items[0].operation").value("APPROVE"))
        .andExpect(jsonPath("$.items[0].lastErrorCode").value(nullValue()));
    mvc.perform(
            replay(
                OPERATOR, "replay-key-0001", "{\"expectedVersion\":4,\"reason\":\"PG_RECOVERED\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.version").value(5));
    verify(jobs).replay("operator:ops-1", JOB, "replay-key-0001", 4, "PG_RECOVERED");

    mvc.perform(as(SETTLEMENT, get("/internal/payment-jobs"))).andExpect(status().isForbidden());
    mvc.perform(as(GATEWAY, get("/internal/payment-jobs"))).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("재처리의 멱등 키·본문 오류는 400, 같은 키 다른 본문은 409다")
  void rejectsInvalidReplay() throws Exception {
    mvc.perform(
            as(OPERATOR, post("/internal/payment-jobs/{id}/replays", JOB))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4,\"reason\":\"PG_RECOVERED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    mvc.perform(replay(OPERATOR, "short", "{\"expectedVersion\":4,\"reason\":\"PG_RECOVERED\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(replay(OPERATOR, "replay-key-0001", "{\"expectedVersion\":-1,\"reason\":\"재시도\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(2));
    verifyNoInteractions(jobs);

    given(jobs.replay("operator:ops-1", JOB, "replay-key-0001", 4, "OTHER"))
        .willThrow(new IdempotencyConflictException());
    mvc.perform(replay(OPERATOR, "replay-key-0001", "{\"expectedVersion\":4,\"reason\":\"OTHER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));
  }

  @ParameterizedTest
  @CsvSource({
    "55P03,503,COMMON_TEMPORARILY_UNAVAILABLE",
    "57014,503,COMMON_TEMPORARILY_UNAVAILABLE",
    "42601,500,COMMON_INTERNAL_ERROR"
  })
  @DisplayName("잠금·실행 시간 초과는 503으로 응답하고 다른 SQL 결함은 500으로 유지한다")
  void distinguishesSqlTimeoutFromDefect(String sqlState, int httpStatus, String code)
      throws Exception {
    given(jobs.replay("operator:ops-1", JOB, "replay-key-0001", 4, "PG_RECOVERED"))
        .willThrow(
            new UncategorizedSQLException(
                "replay", "internal query", new SQLException("database failure", sqlState)));

    mvc.perform(
            replay(
                OPERATOR, "replay-key-0001", "{\"expectedVersion\":4,\"reason\":\"PG_RECOVERED\"}"))
        .andExpect(status().is(httpStatus))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal query"))));
  }

  private static MockHttpServletRequestBuilder replay(String credential, String key, String body) {
    return as(credential, post("/internal/payment-jobs/{id}/replays", JOB))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static MockHttpServletRequestBuilder as(
      String credential, MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
  }

  private static MockHttpServletRequestBuilder asBuyer(MockHttpServletRequestBuilder request) {
    return as(GATEWAY, request)
        .header(AuthenticatedBuyerResolver.ACTOR_KIND, "BUYER")
        .header(AuthenticatedBuyerResolver.ACTOR_ID, BUYER.toString());
  }
}
