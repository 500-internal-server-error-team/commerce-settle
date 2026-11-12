package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.ProductInventory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Inventory")
public record InventoryResponse(
    UUID productId,
    long availableQuantity,
    long reservedQuantity,
    long eventAllocatedQuantity,
    long soldQuantity,
    long version) {
  public static InventoryResponse from(ProductInventory inventory) {
    return new InventoryResponse(
        inventory.getProductId(),
        inventory.getAvailableQuantity(),
        inventory.getReservedQuantity(),
        inventory.getEventAllocatedQuantity(),
        inventory.getSoldQuantity(),
        inventory.getVersion());
  }
}
