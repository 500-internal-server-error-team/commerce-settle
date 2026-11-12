package com.commercesettle.payment.presentation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.internalauth.InternalAuthAutoConfiguration;
import com.commercesettle.payment.application.port.QuarantineStore.DeliveryView;
import com.commercesettle.payment.application.port.QuarantineStore.QuarantineView;
import com.commercesettle.payment.application.service.QuarantineOperationService;
import com.commercesettle.payment.application.service.QuarantineOperationService.QuarantinePage;
import com.commercesettle.payment.domain.exception.IdempotencyConflictException;
import com.commercesettle.payment.domain.exception.QuarantineNotFoundException;
import com.commercesettle.payment.domain.exception.QuarantineOperationRejectedException;
import com.commercesettle.payment.domain.exception.QuarantineOperationRejectedException.Reason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 격리 원문 운영 API의 응답 형식, 오류 code, 명세 x-actor에 따른 발신자 판정을 HTTP 경계에서 확인한다. */
@WebMvcTest(
    controllers = PaymentQuarantineController.class,
    properties = {
      "app.internal-auth.service=payment",
      "app.internal-auth.senders.gateway.current=" + PaymentQuarantineControllerTest.GATEWAY,
      "app.internal-auth.senders[operator\\:ops-1].current="
          + PaymentQuarantineControllerTest.OPERATOR
    })
@Import(InternalAuthAutoConfiguration.class)
class PaymentQuarantineControllerTest {
  static final String GATEWAY = "gateway-credential-0123456789abcdef0123456789";
  static final String OPERATOR = "operator-credential-0123456789abcdef01234567";
  private static final UUID QUARANTINE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID DELIVERY = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
  private static final Instant AT = Instant.parse("2026-11-03T01:00:00Z");
  private static final String BODY = "{\"expectedVersion\":0,\"reasonCode\":\"SCHEMA_FIXED\"}";

  @Autowired MockMvc mvc;
  @MockitoBean QuarantineOperationService quarantines;

  private static DeliveryView delivery(String kind, String status) {
    return new DeliveryView(
        DELIVERY,
        QUARANTINE,
        kind,
        kind.equals("DLT") ? "commerce-settle.payment.v1.dlt" : "commerce-settle.commerce.v1",
        status,
        0,
        AT,
        null,
        kind.equals("DLT") ? null : "operator:ops-1",
        kind.equals("DLT") ? null : "SCHEMA_FIXED",
        1,
        AT,
        AT,
        List.of());
  }

  private static QuarantineView quarantine(DeliveryView... deliveries) {
    return new QuarantineView(
        QUARANTINE,
        "payment.v1",
        "commerce-settle.commerce.v1",
        0,
        41,
        "PAYMENT_EVENT_INVALID",
        10,
        "ab".repeat(32),
        1,
        AT,
        List.of(deliveries));
  }

  @Test
  @DisplayName("운영자는 원문 bytes 없이 크기·SHA-256과 발행 작업을 조회한다")
  void operatorReadsQuarantineWithoutRawBody() throws Exception {
    given(quarantines.list("REVIEW_REQUIRED", 0, 20))
        .willReturn(
            new QuarantinePage(List.of(quarantine(delivery("DLT", "REVIEW_REQUIRED"))), 0, 20, 1));
    given(quarantines.get(QUARANTINE)).willReturn(quarantine(delivery("DLT", "PUBLISHED")));

    mvc.perform(
            as(
                OPERATOR,
                get("/internal/payment-quarantines").param("deliveryStatus", "REVIEW_REQUIRED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(QUARANTINE.toString()))
        .andExpect(jsonPath("$.items[0].bodySize").value(10))
        .andExpect(jsonPath("$.items[0].rawBody").doesNotExist())
        .andExpect(jsonPath("$.items[0].messageKey").doesNotExist())
        .andExpect(jsonPath("$.totalPages").value(1));
    mvc.perform(as(OPERATOR, get("/internal/payment-quarantines/{id}", QUARANTINE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveries[0].kind").value("DLT"))
        .andExpect(jsonPath("$.deliveries[0].operatorId").value(nullValue()))
        .andExpect(jsonPath("$.deliveries[0].leaseUntil").value(nullValue()))
        .andExpect(jsonPath("$.createdAt").value("2026-11-03T10:00:00+09:00"));
  }

  @Test
  @DisplayName("운영자는 확인된 운영자 ID와 멱등 키로 재처리·재개를 접수한다")
  void operatorReplaysAndResumes() throws Exception {
    given(quarantines.replay("operator:ops-1", QUARANTINE, "replay-key-0001", 0, "SCHEMA_FIXED"))
        .willReturn(quarantine(delivery("REPLAY", "PENDING")));
    given(quarantines.resume("operator:ops-1", DELIVERY, "resume-key-0001", 0, "SCHEMA_FIXED"))
        .willReturn(delivery("DLT", "PENDING"));

    mvc.perform(
            write(
                OPERATOR,
                "/internal/payment-quarantines/{id}/replays",
                QUARANTINE,
                "replay-key-0001",
                BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.deliveries[0].kind").value("REPLAY"))
        .andExpect(jsonPath("$.deliveries[0].operatorId").value("operator:ops-1"));
    mvc.perform(
            write(
                OPERATOR,
                "/internal/payment-quarantine-deliveries/{id}/resumes",
                DELIVERY,
                "resume-key-0001",
                BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"));
    verify(quarantines).replay("operator:ops-1", QUARANTINE, "replay-key-0001", 0, "SCHEMA_FIXED");
  }

  @Test
  @DisplayName("Gateway 자격은 격리 운영 API를 부를 수 없다")
  void rejectsGatewayCredential() throws Exception {
    mvc.perform(as(GATEWAY, get("/internal/payment-quarantines")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    mvc.perform(
            write(
                GATEWAY,
                "/internal/payment-quarantines/{id}/replays",
                QUARANTINE,
                "replay-key-0001",
                BODY))
        .andExpect(status().isForbidden());
    verifyNoInteractions(quarantines);
  }

  @ParameterizedTest
  @EnumSource(Reason.class)
  @DisplayName("재처리 거절은 409와 사유별 code로 응답한다")
  void mapsRejectionsToConflictCodes(Reason reason) throws Exception {
    given(quarantines.replay(anyString(), any(), anyString(), anyLong(), anyString()))
        .willThrow(new QuarantineOperationRejectedException(reason));

    mvc.perform(
            write(
                OPERATOR,
                "/internal/payment-quarantines/{id}/replays",
                QUARANTINE,
                "replay-key-0001",
                BODY))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(reason.code()))
        .andExpect(
            jsonPath("$.instance")
                .value("/internal/payment-quarantines/" + QUARANTINE + "/replays"))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  @DisplayName("같은 멱등 키 다른 본문의 재개는 409 COMMON_IDEMPOTENCY_CONFLICT다")
  void mapsIdempotencyConflict() throws Exception {
    given(quarantines.resume(anyString(), any(), anyString(), anyLong(), anyString()))
        .willThrow(new IdempotencyConflictException());

    mvc.perform(
            write(
                OPERATOR,
                "/internal/payment-quarantine-deliveries/{id}/resumes",
                QUARANTINE,
                "resume-key-0001",
                BODY))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  @DisplayName("없는 원문은 404, 본문·멱등 키·발행 상태 형식 오류는 400이다")
  void rejectsMissingAndInvalidRequests() throws Exception {
    given(quarantines.get(QUARANTINE)).willThrow(new QuarantineNotFoundException());

    mvc.perform(as(OPERATOR, get("/internal/payment-quarantines/{id}", QUARANTINE)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));
    mvc.perform(
            write(
                OPERATOR,
                "/internal/payment-quarantines/{id}/replays",
                QUARANTINE,
                "replay-key-0001",
                "{\"expectedVersion\":-1,\"reasonCode\":\"bad reason\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors.length()").value(2));
    mvc.perform(
            write(
                OPERATOR, "/internal/payment-quarantines/{id}/replays", QUARANTINE, "short", BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(
            as(OPERATOR, post("/internal/payment-quarantines/{id}/replays", QUARANTINE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(as(OPERATOR, get("/internal/payment-quarantines").param("deliveryStatus", "DONE")))
        .andExpect(status().isBadRequest());
    // 형식 오류는 서비스까지 가지 않는다. 404 확인을 위한 조회 한 번만 호출됐다.
    verify(quarantines).get(QUARANTINE);
    verifyNoMoreInteractions(quarantines);
  }

  private static MockHttpServletRequestBuilder write(
      String credential, String path, UUID id, String key, String body) {
    return as(credential, post(path, id))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static MockHttpServletRequestBuilder as(
      String credential, MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
  }
}
