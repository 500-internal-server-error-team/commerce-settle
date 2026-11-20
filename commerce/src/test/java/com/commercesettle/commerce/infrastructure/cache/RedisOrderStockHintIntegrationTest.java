package com.commercesettle.commerce.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Timeout(30)
class RedisOrderStockHintIntegrationTest {
  private static final String ADMIN_PASSWORD = "test-redis-admin";
  private static final String COMMERCE_PASSWORD = "test-redis-commerce";
  private static final UUID PRODUCT = UUID.randomUUID();
  private static final String KEY = "commerce-settle:order-stock-hint:" + PRODUCT;

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.2-alpine")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--requirepass",
              ADMIN_PASSWORD,
              "--user",
              "commerce",
              "on",
              ">" + COMMERCE_PASSWORD,
              "resetkeys",
              "~commerce-settle:order-stock-hint:*",
              "-@all",
              "+@connection",
              "+get",
              "+decrby",
              "+eval",
              "+evalsha",
              "+info");

  @Test
  @DisplayName("Lua는 동시 주문의 시험용 수량만 차감하고 키 TTL을 유지한다")
  void probesConcurrentRequestsOnlyForTheEnabledProduct() throws Exception {
    var admin = factory("default", ADMIN_PASSWORD);
    var commerce = factory("commerce", COMMERCE_PASSWORD);
    try {
      var redis = new StringRedisTemplate(admin);
      var hint = new RedisOrderStockHint(new StringRedisTemplate(commerce), PRODUCT.toString());
      var disabled = new RedisOrderStockHint(new StringRedisTemplate(commerce), "");
      redis.opsForValue().set(KEY, "100", Duration.ofMinutes(1));

      assertThat(disabled.suggestsShortage(PRODUCT, 10)).isFalse();
      assertThat(hint.suggestsShortage(UUID.randomUUID(), 10)).isFalse();
      assertThat(redis.opsForValue().get(KEY)).isEqualTo("100");

      var requests =
          IntStream.range(0, 20)
              .mapToObj(i -> (Callable<Boolean>) () -> hint.suggestsShortage(PRODUCT, 10))
              .toList();
      try (var pool = Executors.newFixedThreadPool(10)) {
        var responses = pool.invokeAll(requests);
        int shortages = 0;
        for (var response : responses) {
          if (response.get()) {
            shortages++;
          }
        }
        assertThat(shortages).isEqualTo(10);
      }
      assertThat(redis.opsForValue().get(KEY)).isEqualTo("0");
      assertThat(redis.getExpire(KEY)).isBetween(1L, 60L);
    } finally {
      commerce.destroy();
      admin.destroy();
    }
  }

  @Test
  @DisplayName("시험용 키가 없거나 형식이 잘못되거나 Lua가 실패하면 기존 경로를 사용한다")
  void bypassesMissingMalformedAndUnreadableCounters() {
    var admin = factory("default", ADMIN_PASSWORD);
    var commerce = factory("commerce", COMMERCE_PASSWORD);
    try {
      var redis = new StringRedisTemplate(admin);
      var hint = new RedisOrderStockHint(new StringRedisTemplate(commerce), PRODUCT.toString());
      redis.delete(KEY);
      assertThat(hint.suggestsShortage(PRODUCT, 10)).isFalse();

      redis.opsForValue().set(KEY, "invalid");
      assertThat(hint.suggestsShortage(PRODUCT, 10)).isFalse();

      redis.delete(KEY);
      redis.opsForList().leftPush(KEY, "0");
      assertThat(hint.suggestsShortage(PRODUCT, 10)).isFalse();
    } finally {
      commerce.destroy();
      admin.destroy();
    }
  }

  private static LettuceConnectionFactory factory(String user, String password) {
    var config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    config.setUsername(user);
    config.setPassword(RedisPassword.of(password));
    var connection = new LettuceConnectionFactory(config);
    connection.afterPropertiesSet();
    return connection;
  }
}
