package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.port.NotificationStore.Draft;
import com.commercesettle.commerce.domain.model.ActorKind;

/** 고정 템플릿. 판매자 렌더링에는 구매자 지갑 정보를 사용하지 않는다. */
public final class NotificationTemplates {
  public static final String VERSION = "transaction-v1";
  public static final String SELF_VERSION = "transaction-self-v1";

  private NotificationTemplates() {}

  public static Rendered render(Draft draft, int count, long total) {
    return draft.destination().channel()
            == com.commercesettle.commerce.domain.model.Notification.Channel.KAKAO_SELF
        ? renderSelf(draft, count, total)
        : renderMock(draft, count, total);
  }

  public static String version(Draft draft) {
    return draft.destination().channel()
            == com.commercesettle.commerce.domain.model.Notification.Channel.KAKAO_SELF
        ? SELF_VERSION
        : VERSION;
  }

  private static Rendered renderMock(Draft draft, int count, long total) {
    var p = draft.payload();
    String reference = draft.businessRef().toString();
    var rendered =
        switch (draft.kind()) {
          case PAYMENT_COMPLETED ->
              new Rendered(
                  "결제 완료",
                  "주문 "
                      + p.path("orderId").asText()
                      + " 결제가 완료됐습니다. 외부 결제 "
                      + p.path("externalAmount").asLong()
                      + "원, 사용 마일리지 "
                      + p.path("mileageAmount").asLong()
                      + "원."
                      + balance(draft));
          case REFUND_COMPLETED ->
              new Rendered(
                  "환불 완료",
                  "환불 "
                      + reference
                      + "의 금융 환불과 주문 복구가 완료됐습니다. 환불액 "
                      + p.path("totalAmount").asLong()
                      + "원."
                      + (draft.recipientKind() == ActorKind.BUYER
                          ? " 외부 환불 "
                              + p.path("externalAmount").asLong()
                              + "원, 복원 마일리지 "
                              + p.path("mileageAmount").asLong()
                              + "원."
                          : "")
                      + balance(draft));
          case SHIPMENT_STARTED ->
              new Rendered("발송 시작", "주문 " + reference + "의 발송이 처리됐습니다. 배송 완료는 주문 내역에서 확인해 주세요.");
          case SHIPMENT_DELIVERED ->
              new Rendered("배송 완료", "주문 " + reference + "이 배송 완료됐습니다. 주문 내역과 반품 기한을 확인해 주세요.");
          case PURCHASE_CONFIRMED ->
              new Rendered("구매확정", "주문 " + reference + "의 환불 기한이 지나 자동 구매확정됐습니다.");
          case CANCELLATION_COMPLETED ->
              new Rendered("주문 취소 완료", "미결제 주문 " + reference + "의 승인 차단과 예약 해제가 확인돼 취소됐습니다.");
          case RETURN_REQUESTED ->
              new Rendered("취소·반품 요청 접수", "요청 " + reference + "이 접수됐습니다. 요청 내역에서 처리 상태를 확인해 주세요.");
          case RETURN_DECIDED ->
              new Rendered(
                  "반품 처리 결정",
                  "요청 "
                      + reference
                      + ("APPROVED".equals(p.path("decision").asText())
                          ? "이 승인됐습니다. 환불 완료는 별도로 안내합니다."
                          : "이 거절됐습니다. 요청 내역을 확인해 주세요."));
          case RETURN_RECEIVED ->
              new Rendered("반품 입고 확인", "요청 " + reference + "의 반품이 입고됐습니다. 환불 완료 여부는 별도로 안내합니다.");
          case REFUND_ACTION_REQUIRED ->
              new Rendered(
                  "환불 확인 필요",
                  "환불 " + reference + "이 확정 실패해 아직 환불되지 않았습니다. 요청 내역을 확인하고 고객 문의를 진행해 주세요.");
          case SALE_COMPLETED ->
              new Rendered(
                  "판매 완료",
                  "1분 동안 접수한 판매 " + count + "건, 주문 결제액 합계 " + total + "원입니다. 환불은 별도로 안내합니다.");
          case SETTLEMENT_READY ->
              new Rendered(
                  "정산 대상 확정",
                  p.path("batchDate").asText()
                      + " 정산 대상 "
                      + p.path("amount").asLong()
                      + "원이 확정돼 지급 처리 중입니다. 마감 "
                      + kst(p.path("cutoffAt").asText())
                      + ".");
          case PAYOUT_COMPLETED ->
              new Rendered(
                  "지급 완료",
                  "지급 "
                      + reference
                      + "의 "
                      + p.path("amount").asLong()
                      + "원이 지급됐습니다. 최초 성공 시각 "
                      + kst(p.path("succeededAt").asText())
                      + ".");
          case PAYOUT_ACTION_REQUIRED ->
              new Rendered(
                  "지급 확인 필요", "정산 배치 " + reference + "의 지급이 확정 거절됐습니다. 정산 내역과 문의 경로를 확인해 주세요.");
        };
    return new Rendered(
        rendered.title(),
        rendered.body()
            + (draft.kind()
                    == com.commercesettle.commerce.domain.model.Notification.Kind.SALE_COMPLETED
                ? " 첫 거래 발생 시각 "
                : " 거래 발생 시각 ")
            + kst(draft.occurredAt().atOffset(java.time.ZoneOffset.UTC).toString())
            + ".");
  }

  private static Rendered renderSelf(Draft draft, int count, long total) {
    var p = draft.payload();
    var standard = renderMock(draft, count, total);
    boolean buyer = draft.recipientKind() == ActorKind.BUYER;
    String context =
        switch (draft.kind()) {
          case SETTLEMENT_READY, PAYOUT_COMPLETED, PAYOUT_ACTION_REQUIRED ->
              "배치 " + p.path("batchDate").asText() + " / " + draft.businessRef();
          default ->
              "주문 "
                  + p.path("orderId").asText(draft.businessRef().toString())
                  + "\n상품 "
                  + shortName(p.path("productName").asText("주문 내역 참조"));
        };
    String amount =
        switch (draft.kind()) {
          case PAYMENT_COMPLETED ->
              "외부 "
                  + p.path("externalAmount").asLong()
                  + "원 · 마일리지 "
                  + p.path("mileageAmount").asLong()
                  + "원"
                  + shortBalance(draft);
          case REFUND_COMPLETED ->
              "환불 "
                  + p.path("totalAmount").asLong()
                  + "원"
                  + (buyer
                      ? "\n외부 "
                          + p.path("externalAmount").asLong()
                          + "원 · 마일리지 "
                          + p.path("mileageAmount").asLong()
                          + "원"
                          + shortBalance(draft)
                      : "");
          case SALE_COMPLETED -> "판매 " + count + "건 · 합계 " + total + "원 (1분 묶음·첫 주문)";
          case SETTLEMENT_READY, PAYOUT_COMPLETED -> "금액 " + p.path("amount").asLong() + "원";
          case PAYOUT_ACTION_REQUIRED -> "지급 미완료 " + p.path("amount").asLong() + "원 · 문의 필요";
          case REFUND_ACTION_REQUIRED -> "환불 미완료 " + p.path("totalAmount").asLong() + "원 · 문의 필요";
          case RETURN_DECIDED ->
              ("APPROVED".equals(p.path("decision").asText()) ? "승인" : "거절")
                  + " · 주문액 "
                  + p.path("totalAmount").asLong()
                  + "원";
          default -> "주문액 " + p.path("totalAmount").asLong() + "원";
        };
    String occurred =
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm 'KST'")
            .withZone(java.time.ZoneId.of("Asia/Seoul"))
            .format(draft.occurredAt());
    return new Rendered(
        standard.title(),
        (buyer ? "[구매자] " : "[판매자] ")
            + standard.title()
            + "\n"
            + context
            + "\n"
            + amount
            + "\n시각 "
            + occurred);
  }

  private static String shortBalance(Draft draft) {
    var balance = draft.payload().path("mileageBalanceAfter");
    return draft.recipientKind() == ActorKind.BUYER && balance.isIntegralNumber()
        ? " · 잔액 " + balance.asLong() + "원"
        : "";
  }

  private static String shortName(String value) {
    String clean = value.replaceAll("\\p{Cntrl}", " ").strip();
    if (clean.length() <= 24) return clean;
    int end = Character.isHighSurrogate(clean.charAt(22)) ? 22 : 23;
    return clean.substring(0, end) + "…";
  }

  private static String balance(Draft draft) {
    var p = draft.payload();
    if (draft.recipientKind() != ActorKind.BUYER
        || p.path("mileageBalanceAfter").isNull()
        || p.path("mileageBalanceAfter").isMissingNode()) return "";
    return " 해당 처리 직후 마일리지 잔액은 " + p.path("mileageBalanceAfter").asLong() + "원입니다.";
  }

  private static String kst(String value) {
    return java.time.OffsetDateTime.parse(value)
        .atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))
        .toOffsetDateTime()
        .toString();
  }

  public record Rendered(String title, String body) {}
}
