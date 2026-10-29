package com.commercesettle.gateway.config;

import com.commercesettle.gateway.filter.SessionVerifier;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 세션 검증 전용 Lettuce 연결. Spring Redis 설정의 host·port·database·계정·시간 제한만 쓰며 url·ssl·sentinel은 지원하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class SessionAuthenticationConfiguration {
  @Bean(destroyMethod = "shutdown")
  RedisClient sessionRedisClient(ClientResources resources, RedisProperties redis) {
    final var client = RedisClient.create(resources);
    final var socket = SocketOptions.builder();
    if (redis.getConnectTimeout() != null) {
      socket.connectTimeout(redis.getConnectTimeout());
    }
    // 끊긴 동안의 명령을 쌓아 재연결 뒤 실행하지 않고 즉시 실패시켜 503으로 거절한다.
    client.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(socket.build())
            .timeoutOptions(TimeoutOptions.enabled())
            .build());
    return client;
  }

  @Bean
  SessionVerifier sessionVerifier(
      RedisClient sessionRedisClient,
      RedisProperties redis,
      @Value("${gateway.auth.session-epoch}") String epoch) {
    final var uri =
        RedisURI.builder()
            .withHost(redis.getHost())
            .withPort(redis.getPort())
            .withDatabase(redis.getDatabase());
    if (redis.getTimeout() != null) {
      uri.withTimeout(redis.getTimeout());
    }
    if (redis.getPassword() != null) {
      if (redis.getUsername() != null) {
        uri.withAuthentication(redis.getUsername(), redis.getPassword());
      } else {
        uri.withPassword(redis.getPassword().toCharArray());
      }
    }
    return new SessionVerifier(sessionRedisClient, uri.build(), UUID.fromString(epoch));
  }
}
