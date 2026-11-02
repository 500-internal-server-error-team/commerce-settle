package com.commercesettle.payment.presentation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.payment.application.dto.PaymentPageView;
import com.commercesettle.payment.application.dto.PaymentView;
import com.commercesettle.payment.application.service.PaymentQueryService;
import com.commercesettle.payment.domain.exception.PaymentNotFoundException;
import com.commercesettle.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final PaymentView MILEAGE_ONLY =
      new PaymentView(
          PAYMENT,
          ORDER,
          PaymentStatus.CONFIRMED,
          10_000,
          10_000,
          0,
          null,
          null,
          Instant.parse("2026-11-02T01:00:00Z"),
          1,
          Instant.parse("2026-11-02T00:59:59.123456Z"),
          0,
          10_000);

  @Autowired MockMvc mvc;
  @MockitoBean PaymentQueryService payments;

  @Test
  @DisplayName("본인 결제를 KST 시각과 null 필드까지 담아 반환한다")
  void returnsOwnPayment() throws Exception {
    given(payments.get(BUYER, PAYMENT)).willReturn(MILEAGE_ONLY);

    mvc.perform(asBuyer(get("/api/payments/{id}", PAYMENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.orderId").value(ORDER.toString()))
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.totalAmount").value(10_000))
        .andExpect(jsonPath("$.mileageAmount").value(10_000))
        .andExpect(jsonPath("$.externalAmount").value(0))
        .andExpect(jsonPath("$.method").value(nullValue()))
        .andExpect(jsonPath("$.providerId").value(nullValue()))
        .andExpect(jsonPath("$.confirmedAt").value("2026-11-02T10:00:00+09:00"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.createdAt").value("2026-11-02T09:59:59.123456+09:00"))
        .andExpect(jsonPath("$.platformCouponAmount").value(0))
        .andExpect(jsonPath("$.settlementBaseAmount").value(10_000));
  }

  @Test
  @DisplayName("없거나 다른 구매자의 결제는 404 공통 오류로 응답한다")
  void returnsNotFoundForOthersPayment() throws Exception {
    given(payments.get(BUYER, PAYMENT)).willThrow(new PaymentNotFoundException());

    mvc.perform(asBuyer(get("/api/payments/{id}", PAYMENT)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.instance").value("/api/payments/" + PAYMENT));
  }

  @Test
  @DisplayName("목록은 기본 page 0·size 20으로 조회하고 페이지 값을 담아 반환한다")
  void listsOwnPaymentsWithDefaults() throws Exception {
    given(payments.list(BUYER, null, 0, 20))
        .willReturn(new PaymentPageView(List.of(MILEAGE_ONLY), 0, 20, 21));

    mvc.perform(asBuyer(get("/api/payments")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalElements").value(21))
        .andExpect(jsonPath("$.totalPages").value(2));
  }

  @Test
  @DisplayName("orderId로 본인 주문의 결제만 조회한다")
  void filtersByOrderId() throws Exception {
    given(payments.list(BUYER, ORDER, 1, 5)).willReturn(new PaymentPageView(List.of(), 1, 5, 0));

    mvc.perform(
            asBuyer(get("/api/payments"))
                .param("orderId", ORDER.toString())
                .param("page", "1")
                .param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.totalPages").value(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"page=-1", "size=0", "size=101", "orderId=not-a-uuid", "page=abc"})
  @DisplayName("잘못된 page·size·orderId는 400 공통 오류로 응답한다")
  void rejectsInvalidListParameters(String query) throws Exception {
    mvc.perform(asBuyer(get("/api/payments?" + query)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value(query.substring(0, query.indexOf('='))));
    verifyNoInteractions(payments);
  }

  @Test
  @DisplayName("결제 ID가 UUID가 아니면 400으로 응답한다")
  void rejectsInvalidPaymentId() throws Exception {
    mvc.perform(asBuyer(get("/api/payments/not-a-uuid")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(payments);
  }

  @Test
  @DisplayName("신원 헤더가 없으면 401로 응답한다")
  void rejectsMissingIdentity() throws Exception {
    mvc.perform(get("/api/payments"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(payments);
  }

  @Test
  @DisplayName("판매자 신원이면 403으로 응답한다")
  void rejectsSeller() throws Exception {
    mvc.perform(
            get("/api/payments")
                .header(AuthenticatedBuyerResolver.ACTOR_KIND, "SELLER")
                .header(AuthenticatedBuyerResolver.ACTOR_ID, BUYER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    verifyNoInteractions(payments);
  }

  private static MockHttpServletRequestBuilder asBuyer(MockHttpServletRequestBuilder request) {
    return request
        .header(AuthenticatedBuyerResolver.ACTOR_KIND, "BUYER")
        .header(AuthenticatedBuyerResolver.ACTOR_ID, BUYER.toString());
  }
}
