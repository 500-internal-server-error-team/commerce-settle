package com.commercesettle.commerce.infrastructure.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
  // 끊긴 동안의 명령을 쌓아 재연결 뒤 실행하지 않는다. 세션 발급·삭제·제한을 확정하지 못하면 즉시 503이다.
  @Bean
  LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
    return options ->
        options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }
}
