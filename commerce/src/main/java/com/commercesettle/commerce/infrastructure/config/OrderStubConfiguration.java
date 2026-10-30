package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.port.OrderInventory;
import com.commercesettle.commerce.application.service.OrderStubDataFactory;
import com.commercesettle.commerce.application.service.OrderStubService;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 로컬 주문 stub의 상품 샘플과 메모리 포트를 조립한다. DB 트랜잭션·행 잠금·재고 예약은 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.order-stub.enabled", havingValue = "true")
public class OrderStubConfiguration {
  private final List<Product> products =
      List.of(
          product(3, 2, "수납 상자", 23_000),
          product(23, 22, "키보드", 39_000),
          product(33, 2, "머그컵", 10_000));

  @Bean
  public OrderInventory orderInventory() {
    Map<UUID, Product> byId =
        products.stream()
            .collect(Collectors.toUnmodifiableMap(Product::getId, Function.identity()));
    return productId -> {
      Product product = byId.get(productId);
      if (product == null) {
        throw new ProductNotFoundException(productId);
      }
      return product;
    };
  }

  @Bean
  public OrderStubService orderStubService(
      Clock clock, @Value("${app.order-stub.seed:67}") String seed, OrderInventory inventory) {
    OrderStubDataFactory snapshots = new OrderStubDataFactory(clock, seed);
    return new OrderStubService(inventory, snapshots, snapshots.initialOrders(products));
  }

  private static Product product(int productId, int sellerId, String name, long unitPrice) {
    return Product.register(
        id(productId), id(sellerId), name, unitPrice, ProductStatus.ON_SALE, Instant.EPOCH);
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
