package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaMileageEntryRepository.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MileageEntryRepositoryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private MileageEntryRepository entries;
  @Autowired private EntityManagerFactory factory;
  @Autowired private PlatformTransactionManager manager;

  @Test
  @DisplayName("새 원장 항목은 기존 행 조회 없이 INSERT 한 번으로 저장한다")
  void insertsNewEntryWithoutSelect() {
    Instant now = Instant.parse("2026-11-10T00:00:00Z");
    var wallet = MileageWallet.open(UUID.randomUUID(), 1_000, now);
    var statistics = factory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    new TransactionTemplate(manager)
        .executeWithoutResult(status -> entries.save(MileageEntry.seed(wallet, now)));

    assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    assertThat(entries.countByWalletId(wallet.getId())).isEqualTo(1);
  }
}
