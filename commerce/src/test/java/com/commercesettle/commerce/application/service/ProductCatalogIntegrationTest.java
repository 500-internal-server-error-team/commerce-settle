package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.model.ProductStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 초기 SQL의 제약·잠금과 함께 상품·재고 유즈케이스의 커밋 결과를 확인한다. */
@SpringBootTest(properties = "management.tracing.enabled=false")
@Testcontainers
class ProductCatalogIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired ProductCatalogService catalog;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @Test
  @DisplayName("상품 등록은 재고 0인 재고 행과 멱등 기록을 함께 저장한다")
  void registersProductWithEmptyInventory() {
    UUID seller = UUID.randomUUID();

    Product product = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE);

    ProductInventory inventory = catalog.inventory(seller, product.getId());
    assertThat(product.getVersion()).isZero();
    assertThat(inventory.getAvailableQuantity()).isZero();
    assertThat(inventory.getVersion()).isZero();
    assertThat(count("idempotency_records where resource_id = ?", product.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키와 본문으로 다시 등록하면 기존 상품을 반환하고 새로 만들지 않는다")
  void replaysSameRegistration() {
    UUID seller = UUID.randomUUID();
    String key = key();

    Product first = catalog.register(seller, key, "키보드", 39_000, ProductStatus.ON_SALE);
    Product replay = catalog.register(seller, key, "키보드", 39_000, ProductStatus.ON_SALE);

    assertThat(replay.getId()).isEqualTo(first.getId());
    assertThat(count("products where seller_id = ?", seller)).isEqualTo(1);
    assertThat(count("product_inventory where product_id = ?", first.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키에 다른 본문이 오면 충돌로 거절하고 상품을 만들지 않는다")
  void rejectsSameKeyWithDifferentBody() {
    UUID seller = UUID.randomUUID();
    String key = key();
    catalog.register(seller, key, "키보드", 39_000, ProductStatus.ON_SALE);

    assertThatThrownBy(() -> catalog.register(seller, key, "키보드", 40_000, ProductStatus.ON_SALE))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(count("products where seller_id = ?", seller)).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 판매자는 같은 멱등키를 각자 사용할 수 있다")
  void scopesKeyBySeller() {
    String key = key();

    Product first = catalog.register(UUID.randomUUID(), key, "키보드", 39_000, ProductStatus.DRAFT);
    Product second = catalog.register(UUID.randomUUID(), key, "키보드", 39_000, ProductStatus.DRAFT);

    assertThat(second.getId()).isNotEqualTo(first.getId());
  }

  @Test
  @DisplayName("같은 키의 동시 등록은 상품을 하나만 만든다")
  void createsOneProductForConcurrentSameKey() throws Exception {
    UUID seller = UUID.randomUUID();
    String key = key();

    List<Object> results =
        runConcurrently(
            2, () -> catalog.register(seller, key, "키보드", 39_000, ProductStatus.ON_SALE).getId());

    assertThat(count("products where seller_id = ?", seller)).isEqualTo(1);
    UUID productId =
        jdbc.queryForObject(
            "select id from commerce.products where seller_id = ?", UUID.class, seller);
    assertThat(results)
        .allSatisfy(
            result ->
                assertThat(result)
                    .satisfiesAnyOf(
                        value -> assertThat(value).isEqualTo(productId),
                        value ->
                            assertThat(value).isInstanceOf(IdempotencyConflictException.class)));
  }

  @Test
  @DisplayName("변경은 버전을 올리고 이전 버전으로 다시 변경하면 충돌한다")
  void changesWithVersionCheck() {
    UUID seller = UUID.randomUUID();
    Product product = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.DRAFT);

    Product changed =
        catalog.change(seller, product.getId(), key(), "무선 키보드", 45_000, ProductStatus.ON_SALE, 0);

    assertThat(changed.getVersion()).isEqualTo(1);
    assertThat(catalog.onSaleProduct(product.getId()).getUnitPrice()).isEqualTo(45_000);
    assertThatThrownBy(
            () ->
                catalog.change(
                    seller, product.getId(), key(), "키보드", 1_000, ProductStatus.ON_SALE, 0))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("다른 판매자의 상품은 조회·변경·재고 조정 모두 찾을 수 없다")
  void hidesOtherSellersProduct() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID productId = catalog.register(owner, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();

    assertThatThrownBy(() -> catalog.sellerProduct(other, productId))
        .isInstanceOf(ProductNotFoundException.class);
    assertThatThrownBy(
            () -> catalog.change(other, productId, key(), "탈취", 1, ProductStatus.ON_SALE, 0))
        .isInstanceOf(ProductNotFoundException.class);
    assertThatThrownBy(() -> catalog.adjustAvailable(other, productId, key(), 5, 0, "입고"))
        .isInstanceOf(ProductNotFoundException.class);
    assertThat(count("idempotency_records where actor_id = ?", other.toString())).isZero();
  }

  @Test
  @DisplayName("논리삭제한 상품은 공개·판매자 조회에서 제외하고 행은 보존한다")
  void deletesLogically() {
    UUID seller = UUID.randomUUID();
    String key = key();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();

    catalog.delete(seller, productId, key, 0);
    catalog.delete(seller, productId, key, 0);

    assertThatThrownBy(() -> catalog.onSaleProduct(productId))
        .isInstanceOf(ProductNotFoundException.class);
    assertThatThrownBy(() -> catalog.sellerProduct(seller, productId))
        .isInstanceOf(ProductNotFoundException.class);
    assertThat(catalog.sellerProducts(seller, 0, 20).totalElements()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.products where id = ? and deleted_at is not null",
                String.class,
                productId))
        .isEqualTo("DELETED");
  }

  @Test
  @DisplayName("공개 목록은 판매 중인 상품만 최신순으로 반환한다")
  void listsOnlyOnSaleProductsNewestFirst() {
    UUID seller = UUID.randomUUID();
    Product older = catalog.register(seller, key(), "먼저", 1_000, ProductStatus.ON_SALE);
    catalog.register(seller, key(), "미공개", 1_000, ProductStatus.DRAFT);
    Product newer = catalog.register(seller, key(), "나중", 1_000, ProductStatus.ON_SALE);

    List<UUID> onSale =
        catalog.onSaleProducts(0, 100).items().stream().map(Product::getId).toList();

    assertThat(onSale).containsSubsequence(newer.getId(), older.getId());
    assertThat(catalog.sellerProducts(seller, 0, 2).totalElements()).isEqualTo(3);
    assertThat(catalog.sellerProducts(seller, 0, 2).totalPages()).isEqualTo(2);
  }

  @Test
  @DisplayName("재고 조정은 가용 수량과 멱등 기록을 업무 참조로 한 이력을 함께 저장한다")
  void adjustsInventoryWithMovement() {
    UUID seller = UUID.randomUUID();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();

    ProductInventory inventory = catalog.adjustAvailable(seller, productId, key(), 10, 0, "입고");

    assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
    assertThat(inventory.getVersion()).isEqualTo(1);
    assertThat(
            count(
                "inventory_movements m join commerce.idempotency_records r on r.id = m.business_ref"
                    + " where m.product_id = ? and m.available_delta = 10"
                    + " and m.operation = 'SELLER_ADJUSTMENT'",
                productId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키로 재고 조정을 재요청하면 다시 반영하지 않는다")
  void replaysAdjustmentWithoutApplyingTwice() {
    UUID seller = UUID.randomUUID();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();
    String key = key();

    catalog.adjustAvailable(seller, productId, key, 10, 0, "입고");
    ProductInventory replay = catalog.adjustAvailable(seller, productId, key, 10, 0, "입고");

    assertThat(replay.getAvailableQuantity()).isEqualTo(10);
    assertThat(count("inventory_movements where product_id = ?", productId)).isEqualTo(1);
  }

  @Test
  @DisplayName("가용 수량보다 많이 줄이면 거절하고 이력·멱등 기록을 남기지 않는다")
  void rejectsInsufficientInventoryWithoutSideEffects() {
    UUID seller = UUID.randomUUID();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();
    catalog.adjustAvailable(seller, productId, key(), 3, 0, "입고");
    String key = key();

    assertThatThrownBy(() -> catalog.adjustAvailable(seller, productId, key, -4, 1, "폐기"))
        .isInstanceOf(InsufficientInventoryException.class);
    assertThat(catalog.inventory(seller, productId).getAvailableQuantity()).isEqualTo(3);
    assertThat(count("inventory_movements where product_id = ?", productId)).isEqualTo(1);
    assertThat(
            count(
                "idempotency_records where operation = 'INVENTORY_ADJUST' and resource_id = ?",
                productId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("같은 버전에 대한 동시 재고 조정은 하나만 반영한다")
  void appliesOneOfConcurrentAdjustmentsOnSameVersion() throws Exception {
    UUID seller = UUID.randomUUID();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();

    List<Object> results =
        runConcurrently(
            2, () -> catalog.adjustAvailable(seller, productId, key(), 5, 0, "입고").getVersion());

    assertThat(results).filteredOn(VersionConflictException.class::isInstance).hasSize(1);
    assertThat(catalog.inventory(seller, productId).getAvailableQuantity()).isEqualTo(5);
    assertThat(count("inventory_movements where product_id = ?", productId)).isEqualTo(1);
  }

  @Test
  @Timeout(30)
  @DisplayName("다른 트랜잭션이 재고 행을 잠그고 있으면 대기 상한 안에 거절하고 아무것도 남기지 않는다")
  void rejectsAdjustmentWithinLockTimeoutWhileInventoryIsLocked() throws Exception {
    UUID seller = UUID.randomUUID();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();

    try (HeldTransaction held =
        holdTransaction(
            () ->
                jdbc.queryForList(
                    "select id from commerce.product_inventory where product_id = ? for update",
                    productId))) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> catalog.adjustAvailable(seller, productId, key(), 5, 0, "입고"))
          .isInstanceOf(PessimisticLockingFailureException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    assertThat(catalog.inventory(seller, productId).getVersion()).isZero();
    assertThat(count("inventory_movements where product_id = ?", productId)).isZero();
    assertThat(
            count(
                "idempotency_records where actor_id = ? and operation = 'INVENTORY_ADJUST'",
                seller.toString()))
        .isZero();
  }

  @Test
  @Timeout(30)
  @DisplayName("같은 키의 요청이 처리 중이면 대기 상한 안에 멱등 충돌로 거절한다")
  void rejectsSameKeyWithinLockTimeoutWhileFirstRequestIsInProgress() throws Exception {
    UUID seller = UUID.randomUUID();
    String sameKey = key();

    try (HeldTransaction held =
        holdTransaction(
            () -> catalog.register(seller, sameKey, "키보드", 39_000, ProductStatus.ON_SALE))) {
      long started = System.nanoTime();
      assertThatThrownBy(
              () -> catalog.register(seller, sameKey, "키보드", 39_000, ProductStatus.ON_SALE))
          .isInstanceOf(IdempotencyConflictException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    assertThat(count("products where seller_id = ?", seller)).isEqualTo(1);
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  /** 모든 작업을 동시에 시작하고 각 결과값 또는 발생한 예외를 모은다. */
  private static List<Object> runConcurrently(int count, Callable<Object> task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(count);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    return task.call();
                  } catch (RuntimeException e) {
                    return e;
                  }
                }));
      }
      start.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<Object> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  /** 다른 스레드의 트랜잭션에서 작업을 실행하고, 닫을 때 커밋할 때까지 잠금을 유지한다. */
  private HeldTransaction holdTransaction(Runnable work) throws Exception {
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> committed =
        executor.submit(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      work.run();
                      acquired.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                      }
                    }));
    if (!acquired.await(10, TimeUnit.SECONDS)) {
      committed.get(1, TimeUnit.SECONDS);
      throw new IllegalStateException("holding transaction did not start");
    }
    return new HeldTransaction(release, committed, executor);
  }

  private record HeldTransaction(
      CountDownLatch release, Future<?> committed, ExecutorService executor)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      release.countDown();
      try {
        committed.get(10, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
