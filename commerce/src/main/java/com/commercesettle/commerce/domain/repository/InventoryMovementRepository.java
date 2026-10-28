package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.InventoryMovement;

public interface InventoryMovementRepository {
  InventoryMovement save(InventoryMovement movement);
}
