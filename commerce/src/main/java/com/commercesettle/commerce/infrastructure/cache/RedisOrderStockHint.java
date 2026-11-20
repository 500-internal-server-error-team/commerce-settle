package com.commercesettle.commerce.infrastructure.cache;

import com.commercesettle.commerce.application.port.OrderStockHint;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisOrderStockHint implements OrderStockHint {
  private static final RedisScript<Long> PROBE =
      RedisScript.of(new ClassPathResource("redis/probe-order-stock.lua"), Long.class);

  private final StringRedisTemplate redis;
  private final UUID testProduct;
  private final List<String> keys;

  RedisOrderStockHint(
      StringRedisTemplate redis, @Value("${app.orders.stock-hint-product:}") String product) {
    this.redis = redis;
    this.testProduct = product.isBlank() ? null : UUID.fromString(product.strip());
    this.keys =
        testProduct == null
            ? List.of()
            : List.of("commerce-settle:order-stock-hint:" + testProduct);
  }

  @Override
  public boolean suggestsShortage(UUID productId, int quantity) {
    if (testProduct == null || !testProduct.equals(productId) || quantity < 1) {
      return false;
    }
    try {
      return Long.valueOf(1L).equals(redis.execute(PROBE, keys, Integer.toString(quantity)));
    } catch (DataAccessException e) {
      // 시험용 힌트가 실패하면 기존 DB 예약 경로를 사용한다.
      return false;
    }
  }
}
