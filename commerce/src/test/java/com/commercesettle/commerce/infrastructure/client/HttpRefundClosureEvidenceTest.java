package com.commercesettle.commerce.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.commercesettle.commerce.application.exception.OrderDependencyUnavailableException;
import com.commercesettle.commerce.application.port.RefundClosureEvidence.Subject;
import com.commercesettle.commerce.application.port.RefundClosureStore;
import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRefundClosureEvidenceTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final Instant now = Instant.parse("2026-11-02T01:00:00.123456Z");
  private final Subject subject =
      new Subject(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          4,
          7);
  private final UUID confirmationId = UUID.randomUUID();
  private final RefundClosureStore store = mock(RefundClosureStore.class);
  private MockRestServiceServer payment;
  private MockRestServiceServer settlement;
  private HttpRefundClosureEvidence adapter;
  private ObjectNode financial;
  private ObjectNode reservation;

  @BeforeEach
  void prepare() {
    var p =
        RestClient.builder()
            .baseUrl("http://payment")
            .defaultHeader("Authorization", "Bearer payment-test");
    var s =
        RestClient.builder()
            .baseUrl("http://settlement")
            .defaultHeader("Authorization", "Bearer settlement-test");
    payment = MockRestServiceServer.bindTo(p).build();
    settlement = MockRestServiceServer.bindTo(s).build();
    adapter =
        new HttpRefundClosureEvidence(
            p.build(), s.build(), store, mapper, Clock.fixed(now, ZoneOffset.UTC));
    confirmation(subject, now);
    financial =
        mapper
            .createObjectNode()
            .put("refundId", subject.refundId().toString())
            .put("requestId", subject.requestId().toString())
            .put("paymentId", subject.paymentId().toString())
            .put("orderId", subject.orderId().toString())
            .put("buyerId", subject.buyerId().toString())
            .put("status", "DECLINED")
            .put("externalStatus", "DECLINED")
            .put("mileageNotRestored", true)
            .put("version", 3)
            .put("decidedAt", now.minusSeconds(2).toString());
    reservation =
        mapper
            .createObjectNode()
            .put("refundId", subject.refundId().toString())
            .put("reservationId", UUID.randomUUID().toString())
            .put("paymentId", subject.paymentId().toString())
            .put("orderId", subject.orderId().toString())
            .put("status", "RELEASED")
            .put("itemStatus", "WAITING_CONFIRMATION")
            .putNull("payoutId")
            .put("activeReservation", false)
            .put("reservationVersion", 1)
            .put("itemVersion", 2)
            .put("releasedAt", now.minusSeconds(1).toString());
  }

  @Test
  @DisplayName("두 소유 서비스의 동일 증빙과 구매자 확인을 보존 가능한 사본으로 반환한다")
  void preservesVerifiedOwnerSnapshotsAndBuyerConfirmation() throws Exception {
    payment
        .expect(ExpectedCount.twice(), requestTo(paymentUrl()))
        .andExpect(header("Authorization", "Bearer payment-test"))
        .andRespond(withSuccess(financial.toString(), MediaType.APPLICATION_JSON));
    settlement
        .expect(ExpectedCount.twice(), requestTo(settlementUrl()))
        .andExpect(header("Authorization", "Bearer settlement-test"))
        .andRespond(withSuccess(reservation.toString(), MediaType.APPLICATION_JSON));

    var evidence = adapter.verify(subject, confirmationId.toString());

    assertThat(evidence.safe()).isTrue();
    var snapshot = mapper.readTree(evidence.snapshot());
    assertThat(snapshot.path("payment")).isEqualTo(financial);
    assertThat(snapshot.path("settlement")).isEqualTo(reservation);
    assertThat(snapshot.at("/confirmation/subject/buyerId").asText())
        .isEqualTo(subject.buyerId().toString());
    payment.verify();
    settlement.verify();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "refundId",
        "requestId",
        "paymentId",
        "orderId",
        "buyerId",
        "UNKNOWN",
        "RESTORING",
        "externalUnknown",
        "restored",
        "missing",
        "version",
        "ACTIVE",
        "CONSUMED",
        "activeReservation",
        "payoutId",
        "itemStatus",
        "reservationVersion",
        "releasedAt",
        "decidedAt"
      })
  @DisplayName("다른 원환불·미확정·미해제·복원·불완전 증빙은 종결하지 않는다")
  void rejectsUnsafeOwnerEvidence(String condition) {
    switch (condition) {
      case "UNKNOWN", "RESTORING" -> financial.put("status", condition);
      case "restored" -> financial.put("mileageNotRestored", false);
      case "externalUnknown" -> financial.put("externalStatus", "UNKNOWN");
      case "missing" -> financial.remove("externalStatus");
      case "version" -> financial.put("version", -1);
      case "ACTIVE", "CONSUMED" -> reservation.put("status", condition);
      case "activeReservation" -> reservation.put("activeReservation", true);
      case "itemStatus" -> reservation.put("itemStatus", "REFUND_RESERVED");
      case "payoutId" -> reservation.put("payoutId", UUID.randomUUID().toString());
      case "reservationVersion" -> reservation.remove("reservationVersion");
      case "releasedAt", "decidedAt" ->
          (condition.equals("releasedAt") ? reservation : financial)
              .put(condition, now.plusSeconds(1).toString());
      default -> financial.put(condition, UUID.randomUUID().toString());
    }
    payment
        .expect(requestTo(paymentUrl()))
        .andRespond(withSuccess(financial.toString(), MediaType.APPLICATION_JSON));
    settlement
        .expect(requestTo(settlementUrl()))
        .andRespond(withSuccess(reservation.toString(), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> adapter.verify(subject, confirmationId.toString()))
        .isInstanceOf(OrderStateException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("두 번째 확인에서 금융 또는 예약 상태가 바뀌면 거절한다")
  void rejectsChangesDuringVerification(boolean financialChanges) {
    payment
        .expect(requestTo(paymentUrl()))
        .andRespond(withSuccess(financial.toString(), MediaType.APPLICATION_JSON));
    settlement
        .expect(requestTo(settlementUrl()))
        .andRespond(withSuccess(reservation.toString(), MediaType.APPLICATION_JSON));
    payment
        .expect(requestTo(paymentUrl()))
        .andRespond(
            withSuccess(
                (financialChanges ? financial.deepCopy().put("version", 4) : financial).toString(),
                MediaType.APPLICATION_JSON));
    if (!financialChanges)
      settlement
          .expect(requestTo(settlementUrl()))
          .andRespond(
              withSuccess(
                  reservation.deepCopy().put("itemVersion", 3).toString(),
                  MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> adapter.verify(subject, confirmationId.toString()))
        .isInstanceOf(OrderStateException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500, 503})
  @DisplayName("권한 설정·통신 장애는 금융 확정 실패로 해석하지 않는다")
  void failsClosedOnOwnerUnavailable(int status) {
    payment.expect(requestTo(paymentUrl())).andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThatThrownBy(() -> adapter.verify(subject, confirmationId.toString()))
        .isInstanceOf(OrderDependencyUnavailableException.class);
  }

  @Test
  @DisplayName("소유 조회 타임아웃은 미확정으로 중단한다")
  void failsClosedOnTimeout() {
    payment
        .expect(requestTo(paymentUrl()))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withException(
                new java.net.SocketTimeoutException("test timeout")));

    assertThatThrownBy(() -> adapter.verify(subject, confirmationId.toString()))
        .isInstanceOf(OrderDependencyUnavailableException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "buyer", "refund", "version", "early", "future"})
  @DisplayName("고객 확인 누락·다른 주체·다른 환불·오래된 주문 버전과 시점은 거절한다")
  void rejectsCustomerConfirmationMismatch(String condition) {
    switch (condition) {
      case "missing" -> when(store.find(confirmationId)).thenReturn(Optional.empty());
      case "buyer" ->
          confirmation(
              new Subject(
                  subject.requestId(),
                  subject.refundId(),
                  subject.paymentId(),
                  subject.orderId(),
                  UUID.randomUUID(),
                  4,
                  7),
              now);
      case "refund" ->
          confirmation(
              new Subject(
                  subject.requestId(),
                  UUID.randomUUID(),
                  subject.paymentId(),
                  subject.orderId(),
                  subject.buyerId(),
                  4,
                  7),
              now);
      case "version" ->
          confirmation(
              new Subject(
                  subject.requestId(),
                  subject.refundId(),
                  subject.paymentId(),
                  subject.orderId(),
                  subject.buyerId(),
                  4,
                  6),
              now);
      case "early" -> {
        confirmation(subject, now.minusSeconds(10));
        payment
            .expect(requestTo(paymentUrl()))
            .andRespond(withSuccess(financial.toString(), MediaType.APPLICATION_JSON));
        settlement
            .expect(requestTo(settlementUrl()))
            .andRespond(withSuccess(reservation.toString(), MediaType.APPLICATION_JSON));
      }
      default -> confirmation(subject, now.plusSeconds(1));
    }

    assertThatThrownBy(() -> adapter.verify(subject, confirmationId.toString()))
        .isInstanceOf(OrderStateException.class);
  }

  private void confirmation(Subject s, Instant at) {
    when(store.find(confirmationId))
        .thenReturn(Optional.of(new RefundClosureStore.Confirmation(confirmationId, s, at)));
  }

  private String paymentUrl() {
    return "http://payment/internal/refunds/" + subject.refundId() + "/financial-closure-evidence";
  }

  private String settlementUrl() {
    return "http://settlement/internal/refunds/"
        + subject.refundId()
        + "/reservation-closure-evidence";
  }
}
