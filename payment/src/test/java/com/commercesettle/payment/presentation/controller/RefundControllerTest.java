package com.commercesettle.payment.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.payment.application.dto.RefundView;
import com.commercesettle.payment.application.service.RefundQueryService;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import com.commercesettle.payment.domain.model.RefundStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(RefundController.class)
class RefundControllerTest {
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID REFUND = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  @Autowired MockMvc mvc;
  @MockitoBean RefundQueryService refunds;

  @Test
  @DisplayName("본인 환불의 금액·상태·SAGA 단계를 KST 시각으로 반환한다")
  void returnsOwnRefund() throws Exception {
    given(refunds.get(BUYER, REFUND))
        .willReturn(
            Optional.of(
                new RefundView(
                    REFUND,
                    PAYMENT,
                    8_000,
                    2_000,
                    RefundStatus.INTERNAL_PENDING,
                    RefundSagaStep.ORDER_RESTORE_PENDING,
                    3,
                    Instant.parse("2026-11-02T00:59:59.123456Z"))));

    mvc.perform(asBuyer(get("/api/refunds/{id}", REFUND)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(REFUND.toString()))
        .andExpect(jsonPath("$.paymentId").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.externalAmount").value(8_000))
        .andExpect(jsonPath("$.mileageAmount").value(2_000))
        .andExpect(jsonPath("$.status").value("INTERNAL_PENDING"))
        .andExpect(jsonPath("$.sagaStep").value("ORDER_RESTORE_PENDING"))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.createdAt").value("2026-11-02T09:59:59.123456+09:00"));
  }

  @Test
  @DisplayName("없거나 다른 구매자의 환불은 404로 응답한다")
  void returnsNotFoundForOthersRefund() throws Exception {
    given(refunds.get(BUYER, REFUND)).willReturn(Optional.empty());

    mvc.perform(asBuyer(get("/api/refunds/{id}", REFUND))).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("신원 헤더가 없으면 401, 판매자면 403이고 조회하지 않는다")
  void rejectsMissingOrNonBuyerIdentity() throws Exception {
    mvc.perform(get("/api/refunds/{id}", REFUND)).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/refunds/{id}", REFUND)
                .header("X-Authenticated-Actor-Kind", "SELLER")
                .header("X-Authenticated-Actor-Id", BUYER.toString()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(refunds);
  }

  @Test
  @DisplayName("본인 환불 목록의 요약과 KST를 반환하고 권한·페이지 범위를 검사한다")
  void listsOwnedSummaries() throws Exception {
    given(refunds.list(BUYER, 0, 20))
        .willReturn(
            new RefundQueryService.Page(
                java.util.List.of(
                    new RefundQueryService.Summary(
                        REFUND,
                        PAYMENT,
                        8000,
                        2000,
                        "RESERVING",
                        0,
                        Instant.parse("2026-11-02T00:00:00Z"))),
                0,
                20,
                1));
    mvc.perform(asBuyer(get("/api/refunds")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(REFUND.toString()))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-02T09:00:00+09:00"))
        .andExpect(jsonPath("$.items[0].sagaStep").doesNotExist())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(asBuyer(get("/api/refunds").param("page", "-1")))
        .andExpect(status().isBadRequest());
    mvc.perform(asBuyer(get("/api/refunds").param("size", "0"))).andExpect(status().isBadRequest());
    mvc.perform(asBuyer(get("/api/refunds").param("size", "101")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/refunds")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/refunds")
                .header("X-Authenticated-Actor-Kind", "SELLER")
                .header("X-Authenticated-Actor-Id", BUYER.toString()))
        .andExpect(status().isForbidden());
  }

  private static MockHttpServletRequestBuilder asBuyer(MockHttpServletRequestBuilder request) {
    return request
        .header("X-Authenticated-Actor-Kind", "BUYER")
        .header("X-Authenticated-Actor-Id", BUYER.toString());
  }
}
