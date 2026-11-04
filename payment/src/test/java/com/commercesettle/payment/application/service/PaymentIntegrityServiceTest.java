package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.payment.application.port.PaymentIntegrityReader;
import com.commercesettle.payment.application.port.PaymentIntegrityReader.Row;
import com.commercesettle.payment.application.port.ProviderReconciliationReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentIntegrityServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-03T02:00:00Z");
  private static final Instant CUTOFF = NOW.minusSeconds(3600);
  private final PaymentIntegrityReader local = mock(PaymentIntegrityReader.class);
  private final ProviderReconciliationReader provider = mock(ProviderReconciliationReader.class);
  private final PaymentIntegrityService service = new PaymentIntegrityService(local, provider);

  private Row row() {
    return new Row(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "PG_A",
        "APPROVE",
        9000,
        "APPROVED",
        UUID.randomUUID(),
        null);
  }

  private Map<String, Object> transaction(Row r, long amount) {
    return Map.of(
        "id",
        r.externalRef().toString(),
        "businessKey",
        r.businessKey().toString(),
        "providerId",
        r.providerId(),
        "operation",
        r.operation(),
        "amount",
        amount,
        "status",
        "SUCCEEDED",
        "succeededAt",
        CUTOFF.minusSeconds(1).toString());
  }

  @Test
  @DisplayName("안전 여유 전 마감은 기준선을 읽지 않고 준비 전 충돌로 거절한다")
  void rejectsCutoffBeforeSafetyMargin() {
    when(local.now()).thenReturn(NOW);
    assertThatThrownBy(() -> service.read(NOW.minusSeconds(599), null, null, 100, "PAYMENT"))
        .isInstanceOf(
            com.commercesettle.payment.domain.exception.ReconciliationCutoffNotReadyException
                .class);
    org.mockito.Mockito.verifyNoInteractions(provider);
  }

  @Test
  @DisplayName("한 페이지의 원거래를 한 번에 조회하고 금액과 원참조를 비교한다")
  void batchesProviderKeysAndDetectsDifference() {
    var a = row();
    var b = row();
    when(local.now()).thenReturn(NOW);
    when(local.page(eq(CUTOFF), eq(NOW), any(), eq(3))).thenReturn(List.of(a, b, row()));
    when(local.checks()).thenReturn(Map.of("mileageBalance", 0L));
    when(provider.lookup(anyList(), eq(NOW)))
        .thenReturn(List.of(transaction(a, 9000), transaction(b, 9001)));
    var result = service.read(CUTOFF, NOW, null, 2, "PAYMENT");
    assertThat(result.get("items").toString()).contains("MATCH", "DEFECT", "difference=1");
    assertThat(result.get("nextCursor")).isNotNull();
    verify(provider).lookup(List.of(a.businessKey(), b.businessKey()), NOW);
    assertThatThrownBy(
            () ->
                service.read(
                    CUTOFF.minusSeconds(1), NOW, result.get("nextCursor").toString(), 2, "PAYMENT"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("읽기 장애는 금액 0이나 일치로 바뀌지 않는다")
  void preservesUnavailableEvidence() {
    when(local.now()).thenReturn(NOW);
    when(local.page(any(), any(), any(), anyInt())).thenReturn(List.of(row()));
    when(provider.lookup(anyList(), any()))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
    assertThatThrownBy(() -> service.read(CUTOFF, NOW, null, 100, "PAYMENT"))
        .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
  }

  @Test
  @DisplayName("대역에서만 존재하는 원거래도 역방향 검사로 찾는다")
  void detectsOrphanProviderTransaction() {
    var a = row();
    when(local.now()).thenReturn(NOW);
    var page = new java.util.HashMap<String, Object>();
    page.put("items", List.of(transaction(a, 9000)));
    page.put("nextCursor", null);
    page.put("totalElements", 1L);
    page.put("page", 0);
    when(provider.page(CUTOFF, NOW, null, 100)).thenReturn(page);
    when(local.byKeys(anyList(), any(), any())).thenReturn(List.of());
    when(local.checks()).thenReturn(Map.of());
    assertThat(service.read(CUTOFF, NOW, null, 100, "PROVIDER").get("items").toString())
        .contains("ORPHAN_PROVIDER_TRANSACTION");
  }
}
