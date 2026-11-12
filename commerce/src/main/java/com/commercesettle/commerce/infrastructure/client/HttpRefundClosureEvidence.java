package com.commercesettle.commerce.infrastructure.client;

import com.commercesettle.commerce.application.exception.OrderDependencyUnavailableException;
import com.commercesettle.commerce.application.port.RefundClosureEvidence;
import com.commercesettle.commerce.application.port.RefundClosureStore;
import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 소유 서비스의 원상태만 읽는다. 조회 중 변경은 거절하며 자동 재시도·금융 실행은 하지 않는다. */
public final class HttpRefundClosureEvidence implements RefundClosureEvidence {
  private final RestClient payment;
  private final RestClient settlement;
  private final RefundClosureStore store;
  private final ObjectMapper mapper;
  private final Clock clock;

  public HttpRefundClosureEvidence(
      RestClient payment,
      RestClient settlement,
      RefundClosureStore store,
      ObjectMapper mapper,
      Clock clock) {
    this.payment = payment;
    this.settlement = settlement;
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  public Evidence verify(Subject subject, String customerReference) {
    final UUID confirmationId;
    try {
      confirmationId = UUID.fromString(customerReference);
    } catch (IllegalArgumentException e) {
      throw conflict();
    }
    var confirmation = store.find(confirmationId).orElseThrow(HttpRefundClosureEvidence::conflict);
    if (!subject.equals(confirmation.subject())
        || confirmation.confirmedAt().isAfter(clock.instant())) throw conflict();
    var financial = read(payment, subject.refundId(), "financial");
    var reservation = read(settlement, subject.refundId(), "reservation");
    for (var field : List.of("refundId", "requestId", "paymentId", "orderId", "buyerId")) {
      var expected =
          switch (field) {
            case "refundId" -> subject.refundId();
            case "requestId" -> subject.requestId();
            case "paymentId" -> subject.paymentId();
            case "orderId" -> subject.orderId();
            default -> subject.buyerId();
          };
      if (expected == null || !expected.toString().equals(financial.path(field).asText()))
        throw conflict();
    }
    if (!subject.refundId().toString().equals(reservation.path("refundId").asText())
        || !subject.paymentId().toString().equals(reservation.path("paymentId").asText())
        || !subject.orderId().toString().equals(reservation.path("orderId").asText())
        || !"DECLINED".equals(financial.path("status").asText())
        || !"DECLINED".equals(financial.path("externalStatus").asText())
        || !financial.path("mileageNotRestored").isBoolean()
        || !financial.path("mileageNotRestored").booleanValue()
        || !"RELEASED".equals(reservation.path("status").asText())
        || !List.of("WAITING_CONFIRMATION", "PAYABLE")
            .contains(reservation.path("itemStatus").asText())
        || !reservation.has("payoutId")
        || !reservation.path("payoutId").isNull()
        || !reservation.path("activeReservation").isBoolean()
        || reservation.path("activeReservation").booleanValue()
        || !validVersion(financial, "version")
        || !validVersion(reservation, "reservationVersion")
        || !validVersion(reservation, "itemVersion")) throw conflict();
    try {
      UUID.fromString(reservation.required("reservationId").asText());
      var declined = OffsetDateTime.parse(financial.required("decidedAt").asText()).toInstant();
      var released = OffsetDateTime.parse(reservation.required("releasedAt").asText()).toInstant();
      if (confirmation.confirmedAt().isBefore(declined)
          || confirmation.confirmedAt().isBefore(released)) throw conflict();
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw conflict();
    }
    // 소유 서비스의 종결 사실은 불변이다. 재조회로 확인 중 버전·상태 변경도 거절한다.
    if (!financial.equals(read(payment, subject.refundId(), "financial"))
        || !reservation.equals(read(settlement, subject.refundId(), "reservation")))
      throw conflict();
    var reference = UUID.randomUUID();
    final String snapshot;
    try {
      snapshot =
          mapper.writeValueAsString(
              Map.of(
                  "subject",
                  subject,
                  "payment",
                  financial,
                  "settlement",
                  reservation,
                  "confirmation",
                  confirmation,
                  "verifiedAt",
                  clock.instant()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new OrderDependencyUnavailableException();
    }
    return new Evidence(subject.refundId(), true, true, true, true, reference.toString(), snapshot);
  }

  private static boolean validVersion(JsonNode node, String field) {
    return node.path(field).isIntegralNumber()
        && node.path(field).canConvertToLong()
        && node.path(field).longValue() >= 0;
  }

  private static JsonNode read(RestClient client, UUID refund, String kind) {
    try {
      var result =
          client
              .get()
              .uri("/internal/refunds/{refundId}/" + kind + "-closure-evidence", refund)
              .retrieve()
              .body(JsonNode.class);
      if (result == null || !result.isObject()) throw new OrderDependencyUnavailableException();
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.CONFLICT)
        throw conflict();
      throw new OrderDependencyUnavailableException();
    } catch (RestClientException e) {
      throw new OrderDependencyUnavailableException();
    }
  }

  private static OrderStateException conflict() {
    return new OrderStateException(OrderStateException.Reason.STATE_CONFLICT);
  }
}
