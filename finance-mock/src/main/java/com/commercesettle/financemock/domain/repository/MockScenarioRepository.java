package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.MockScenario;
import java.util.Optional;

public interface MockScenarioRepository {
  MockScenario save(MockScenario scenario);

  Optional<MockScenario> find(String providerId, String scenarioVersion);
}
