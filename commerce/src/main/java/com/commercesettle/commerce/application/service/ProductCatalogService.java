package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.model.InventoryMovement;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.repository.InventoryMovementRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.ProductInventoryRepository;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품·재고 유즈케이스. 판매자 작업은 인증된 판매자와 상품 소유자를 대조하며, 타인·삭제 상품은 존재하지 않는 상품과 같게 취급한다. 쓰기 작업은 멱등 기록·업무 변경을 한
 * 트랜잭션에 저장한다.
 */
@Service
public class ProductCatalogService {
  private static final String SELLER = "SELLER";

  private final ProductRepository products;
  private final ProductInventoryRepository inventories;
  private final InventoryMovementRepository movements;
  private final IdempotentRequests idempotency;
  private final Clock clock;

  public ProductCatalogService(
      ProductRepository products,
      ProductInventoryRepository inventories,
      InventoryMovementRepository movements,
      IdempotentRequests idempotency,
      Clock clock) {
    this.products = products;
    this.inventories = inventories;
    this.movements = movements;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResult<Product> onSaleProducts(int page, int size) {
    return products.findOnSale(page, size);
  }

  @Transactional(readOnly = true)
  public Product onSaleProduct(UUID productId) {
    return products
        .findById(productId)
        .filter(Product::isPubliclyVisible)
        .orElseThrow(() -> new ProductNotFoundException(productId));
  }

  @Transactional(readOnly = true)
  public PageResult<Product> sellerProducts(UUID sellerId, int page, int size) {
    return products.findBySeller(sellerId, page, size);
  }

  @Transactional(readOnly = true)
  public Product sellerProduct(UUID sellerId, UUID productId) {
    return ownedProduct(sellerId, productId);
  }

  /** 상품과 모든 수량이 0인 재고 행을 함께 만든다. */
  @Transactional
  public Product register(
      UUID sellerId, String idempotencyKey, String name, long unitPrice, ProductStatus status) {
    UUID productId = UUID.randomUUID();
    return idempotency.execute(
        sellerRequest(
            sellerId, "PRODUCT_REGISTER", idempotencyKey, List.of(name, unitPrice, status)),
        productId,
        201,
        recordId -> {
          Instant now = clock.instant();
          Product product =
              products.save(Product.register(productId, sellerId, name, unitPrice, status, now));
          inventories.save(ProductInventory.empty(UUID.randomUUID(), productId, now));
          return product;
        },
        existingId -> ownedProduct(sellerId, existingId));
  }

  /** 공개 후 종료 전 이벤트가 있으면 거절하는 확인은 선착순 이벤트를 구현할 때 재고 행 잠금 뒤에 추가한다. */
  @Transactional
  public Product change(
      UUID sellerId,
      UUID productId,
      String idempotencyKey,
      String name,
      long unitPrice,
      ProductStatus status,
      long expectedVersion) {
    return idempotency.execute(
        sellerRequest(
            sellerId,
            "PRODUCT_CHANGE",
            idempotencyKey,
            List.of(productId, name, unitPrice, status, expectedVersion)),
        productId,
        200,
        recordId -> {
          lockInventory(sellerId, productId);
          Product product = ownedProduct(sellerId, productId);
          product.change(name, unitPrice, status, expectedVersion, clock.instant());
          return products.save(product);
        },
        existingId -> ownedProduct(sellerId, existingId));
  }

  /** 공개 후 종료 전 이벤트 확인은 {@link #change}와 같은 위치에 추가한다. */
  @Transactional
  public void delete(UUID sellerId, UUID productId, String idempotencyKey, long expectedVersion) {
    idempotency.execute(
        sellerRequest(
            sellerId, "PRODUCT_DELETE", idempotencyKey, List.of(productId, expectedVersion)),
        productId,
        204,
        recordId -> {
          lockInventory(sellerId, productId);
          Product product = ownedProduct(sellerId, productId);
          product.delete(expectedVersion, clock.instant());
          return products.save(product);
        },
        existingId -> null);
  }

  @Transactional(readOnly = true)
  public ProductInventory inventory(UUID sellerId, UUID productId) {
    ownedProduct(sellerId, productId);
    return inventories.findByProductId(productId).orElseThrow(() -> missingInventory(productId));
  }

  /** 일반 가용 수량만 바꾸고 멱등 기록 ID를 업무 참조로 한 변경 이력을 함께 남긴다. */
  @Transactional
  public ProductInventory adjustAvailable(
      UUID sellerId,
      UUID productId,
      String idempotencyKey,
      long delta,
      long expectedVersion,
      String reason) {
    return idempotency.execute(
        sellerRequest(
            sellerId,
            "INVENTORY_ADJUST",
            idempotencyKey,
            List.of(productId, delta, expectedVersion, reason)),
        productId,
        200,
        recordId -> {
          ProductInventory inventory = lockInventory(sellerId, productId);
          InventoryMovement movement =
              inventory.adjustAvailable(
                  UUID.randomUUID(), recordId, delta, reason, expectedVersion, clock.instant());
          movements.save(movement);
          return inventories.save(inventory);
        },
        existingId -> inventory(sellerId, existingId));
  }

  /**
   * 재고 행을 먼저 잠근 뒤 소유 상품을 확인한다. 상품과 재고 행을 함께 다루는 쓰기는 이 순서를 지켜 주문·판매자 변경을 같은 행에서 직렬화하고 잠금 순서 역전을 막는다.
   */
  private ProductInventory lockInventory(UUID sellerId, UUID productId) {
    Optional<ProductInventory> inventory = inventories.findByProductIdForUpdate(productId);
    ownedProduct(sellerId, productId);
    return inventory.orElseThrow(() -> missingInventory(productId));
  }

  private Product ownedProduct(UUID sellerId, UUID productId) {
    return products
        .findById(productId)
        .filter(product -> !product.isDeleted() && product.isOwnedBy(sellerId))
        .orElseThrow(() -> new ProductNotFoundException(productId));
  }

  private static IllegalStateException missingInventory(UUID productId) {
    return new IllegalStateException("inventory row is missing for product " + productId);
  }

  private static IdempotentRequests.Request sellerRequest(
      UUID sellerId, String operation, String idempotencyKey, List<?> body) {
    return new IdempotentRequests.Request(
        SELLER, sellerId.toString(), operation, idempotencyKey, body);
  }
}
