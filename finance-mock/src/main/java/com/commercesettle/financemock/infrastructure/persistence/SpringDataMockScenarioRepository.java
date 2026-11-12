package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockScenario;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMockScenarioRepository extends JpaRepository<MockScenario, UUID> {
  Optional<MockScenario> findByProviderIdAndScenarioVersion(
      String providerId, String scenarioVersion);
}
