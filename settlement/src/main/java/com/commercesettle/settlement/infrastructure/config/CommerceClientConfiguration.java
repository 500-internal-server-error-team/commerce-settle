package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.infrastructure.client.HttpProductSettlementContextReader;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 커머스 내부 조회 클라이언트. 지급 생성 트랜잭션 안에서 호출하므로 연결·응답 대기를 제한한다. */
@Configuration
public class CommerceClientConfiguration {
  @Bean
  ProductSettlementContextReader productSettlementContextReader(
      RestClient.Builder builder,
      @Value("${settlement.commerce.base-url}") String baseUrl,
      @Value("${settlement.commerce.credential}") String credential,
      @Value("${settlement.commerce.http-timeout}") Duration timeout) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(timeout);
    return new HttpProductSettlementContextReader(
        builder.baseUrl(baseUrl).requestFactory(requestFactory).build(), credential);
  }
}
