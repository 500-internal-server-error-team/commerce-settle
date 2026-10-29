package com.commercesettle.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.model.SettlementItemStatus;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 커밋 경계를 확인하기 위해 테스트 트랜잭션 자동 롤백을 끄고 테스트마다 데이터를 지운다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaSettlementItemRepository.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementItemRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final Instant CREATED_AT = Instant.parse("2026-10-28T00:00:00.123456Z");
  private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T00:01:00.654321Z");
  private static final Instant ELIGIBLE_AT = Instant.parse("2026-10-28T00:00:30Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-10-28T00:02:00.000001Z");
  private static final SettlementAmounts AMOUNTS =
      new SettlementAmounts(20_000, 3_000, 23_000, 20_000, 0, 3_450, 19_550);

  @Autowired private SettlementItemRepository settlementItemRepository;
  @Autowired private DataSource dataSource;

  @AfterEach
  void deleteItems() {
    new JdbcTemplate(dataSource).update("DELETE FROM settlement.settlement_items");
  }

  @Test
  @DisplayName("저장한 정산 항목을 다시 읽으면 금액·상태·시각·지급 연결이 보존된다")
  void preservesItemAfterReload() {
    UUID payoutId = UUID.randomUUID();
    SettlementItem item = item(UUID.randomUUID(), AMOUNTS);
    item.markPayable(CONFIRMED_AT, ELIGIBLE_AT, CONFIRMED_AT);
    item.reservePayout(payoutId, CHANGED_AT);
    settlementItemRepository.save(item);

    SettlementItem found = settlementItemRepository.findById(item.getId()).orElseThrow();

    assertThat(found.getAmounts()).isEqualTo(AMOUNTS);
    assertThat(found.getStatus()).isEqualTo(SettlementItemStatus.PAYOUT_RESERVED);
    assertThat(found.getPurchaseConfirmedAt()).isEqualTo(CONFIRMED_AT);
    assertThat(found.getEligibleReceivedAt()).isEqualTo(ELIGIBLE_AT);
    assertThat(found.getReadyAt()).isEqualTo(CONFIRMED_AT);
    assertThat(found.getPayoutId()).isEqualTo(payoutId);
    assertThat(found.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(found.getUpdatedAt()).isEqualTo(CHANGED_AT);
    assertThat(found.getVersion()).isZero();
  }

  @Test
  @DisplayName("결제 ID로 정산 항목을 찾는다")
  void findsByPaymentId() {
    UUID paymentId = UUID.randomUUID();
    SettlementItem item = item(paymentId, AMOUNTS);
    settlementItemRepository.save(item);

    assertThat(settlementItemRepository.findByPaymentId(paymentId))
        .map(SettlementItem::getId)
        .contains(item.getId());
    assertThat(settlementItemRepository.findByPaymentId(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("같은 결제의 정산 항목은 두 번 저장할 수 없다")
  void rejectsDuplicatePayment() {
    UUID paymentId = UUID.randomUUID();
    settlementItemRepository.save(item(paymentId, AMOUNTS));

    assertThatThrownBy(() -> settlementItemRepository.save(item(paymentId, AMOUNTS)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("도메인은 수수료율을 검사하지 않지만 DB는 현재 요율과 다른 수수료를 거절한다")
  void rejectsFeeRateMismatchInDatabase() {
    // B=F+N은 맞지만 수수료가 floor(10,000×15/100)=1,500원이 아니다.
    SettlementAmounts otherRate = new SettlementAmounts(10_000, 0, 10_000, 10_000, 0, 1_000, 9_000);

    assertThatThrownBy(() -> settlementItemRepository.save(item(UUID.randomUUID(), otherRate)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_settlement_items_fee");
  }

  @Test
  @DisplayName("이미 다른 곳에서 수정한 정산 항목을 이전 버전으로 저장하면 충돌한다")
  void rejectsStaleUpdate() {
    SettlementItem item = item(UUID.randomUUID(), AMOUNTS);
    settlementItemRepository.save(item);
    SettlementItem first = settlementItemRepository.findById(item.getId()).orElseThrow();
    SettlementItem second = settlementItemRepository.findById(item.getId()).orElseThrow();

    first.markPayable(CONFIRMED_AT, ELIGIBLE_AT, CHANGED_AT);
    settlementItemRepository.save(first);
    second.reserveRefund(CHANGED_AT);

    assertThatThrownBy(() -> settlementItemRepository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(settlementItemRepository.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(SettlementItemStatus.PAYABLE);
  }

  private static SettlementItem item(UUID paymentId, SettlementAmounts amounts) {
    return SettlementItem.create(
        paymentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amounts, 0, CREATED_AT);
  }
}
