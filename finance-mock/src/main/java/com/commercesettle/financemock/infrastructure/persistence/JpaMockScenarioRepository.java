package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class JpaMockScenarioRepository implements MockScenarioRepository {
  private final SpringDataMockScenarioRepository repository;

  JpaMockScenarioRepository(SpringDataMockScenarioRepository repository) {
    this.repository = repository;
  }

  @Override
  public MockScenario save(MockScenario scenario) {
    return repository.save(scenario);
  }

  @Override
  public Optional<MockScenario> find(String providerId, String scenarioVersion) {
    return repository.findByProviderIdAndScenarioVersion(providerId, scenarioVersion);
  }
}
