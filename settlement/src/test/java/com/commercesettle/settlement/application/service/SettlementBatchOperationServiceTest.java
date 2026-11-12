package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.dto.DailySettlementSettings;
import com.commercesettle.settlement.application.port.AuditLog;
import com.commercesettle.settlement.application.port.IdempotencyStore;
import com.commercesettle.settlement.application.service.SellerSettlementService.Prepared;
import com.commercesettle.settlement.application.service.SellerSettlementService.Settled;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class SettlementBatchOperationServiceTest {
  private static final String POLICY = "delivery-daily-v1";
  private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
  private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final Prepared PREPARED =
      new Prepared(SELLER, DATE, null, null, Set.of(UUID.randomUUID()), null);

  private final SettlementBatchRepository batches = mock(SettlementBatchRepository.class);
  private final SellerSettlementService sellers = mock(SellerSettlementService.class);
  private final DailySettlementSettings settings =
      new DailySettlementSettings(POLICY, "payout-funding-default", "payout-normal-v1");
  private final AuditLog audit = mock(AuditLog.class);
  private SettlementBatchOperationService service;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(batches.find(SELLER, DATE, POLICY)).thenReturn(Optional.empty());
    service =
        new SettlementBatchOperationService(
            batches,
            sellers,
            settings,
            new IdempotentRequests(mock(IdempotencyStore.class), clock),
            audit,
            clock,
            mock(PlatformTransactionManager.class));
    when(sellers.prepare(SELLER, DATE)).thenReturn(PREPARED);
  }

  @Test
  @DisplayName("요청 사이 일배치가 먼저 만든 배치를 받으면 운영자 생성으로 감사하지 않고 그 배치를 돌려준다")
  void doesNotAuditBatchCreatedByDailyRun() {
    SettlementBatch daily = batch();
    when(sellers.settlePrepared(PREPARED)).thenReturn(Optional.of(new Settled(daily, false)));

    assertThat(service.create("operator:ops", "batch-key-0001", SELLER, DATE, POLICY))
        .isSameAs(daily);
    verify(audit, never()).record(any());
  }

  @Test
  @DisplayName("이번 요청이 만든 배치만 감사하고, 추적 ID는 정산 배치 ID다")
  void auditsBatchCreatedByRequest() {
    SettlementBatch created = batch();
    when(sellers.settlePrepared(PREPARED)).thenReturn(Optional.of(new Settled(created, true)));

    service.create("operator:ops", "batch-key-0002", SELLER, DATE, POLICY);

    ArgumentCaptor<AuditLog.Entry> entry = ArgumentCaptor.forClass(AuditLog.Entry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().action()).isEqualTo(AuditLog.Action.SETTLEMENT_BATCH_CREATED);
    assertThat(entry.getValue().correlationId()).isEqualTo(created.getId().toString());
  }

  private static SettlementBatch batch() {
    return SettlementBatch.reserve(SELLER, DATE, POLICY, 1_000, NOW);
  }
}
