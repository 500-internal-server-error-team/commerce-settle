package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.InventoryMovement;
import com.commercesettle.commerce.domain.repository.InventoryMovementRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
class JpaInventoryMovementRepository implements InventoryMovementRepository {
  private final EntityManager entityManager;

  JpaInventoryMovementRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** 추가 전용 기록이므로 기존 행을 병합하지 않고 항상 새로 저장한다. */
  @Override
  public InventoryMovement save(InventoryMovement movement) {
    entityManager.persist(movement);
    return movement;
  }
}
