package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfMessageClient;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoTokenManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.kakao-self.enabled", havingValue = "true")
@EnableConfigurationProperties(KakaoSelfProperties.class)
public class KakaoSelfConfiguration {
  @Bean
  KakaoSelfMessageClient kakaoSelfMessageClient(
      KakaoSelfProperties properties, ObjectMapper mapper, Clock clock, MeterRegistry registry) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    KakaoTokenManager tokens =
        new KakaoTokenManager(
            properties.credentialsFile(),
            properties.userId(),
            properties.appId(),
            http,
            mapper,
            URI.create("https://kauth.kakao.com/oauth/token"),
            URI.create("https://kapi.kakao.com/v1/user/access_token_info"),
            clock,
            registry);
    return new KakaoSelfMessageClient(
        http,
        mapper,
        tokens,
        URI.create("https://kapi.kakao.com/v2/api/talk/memo/default/send"),
        properties.messageLink());
  }
}
