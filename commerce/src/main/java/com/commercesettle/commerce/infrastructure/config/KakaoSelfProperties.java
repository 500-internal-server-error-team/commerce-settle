package com.commercesettle.commerce.infrastructure.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.kakao-self")
public record KakaoSelfProperties(
    Path credentialsFile,
    String userId,
    String appId,
    URI messageLink,
    Set<UUID> buyerIds,
    Set<UUID> sellerIds) {
  public KakaoSelfProperties {
    buyerIds = buyerIds == null ? Set.of() : Set.copyOf(buyerIds);
    sellerIds = sellerIds == null ? Set.of() : Set.copyOf(sellerIds);
    if (credentialsFile == null
        || !credentialsFile.isAbsolute()
        || userId == null
        || !userId.matches("[1-9][0-9]*")
        || appId == null
        || !appId.matches("[1-9][0-9]*")
        || messageLink == null
        || !Set.of("http", "https").contains(messageLink.getScheme())
        || messageLink.getHost() == null
        || messageLink.getUserInfo() != null
        || messageLink.getRawQuery() != null
        || messageLink.getFragment() != null
        || (buyerIds.isEmpty() && sellerIds.isEmpty())) {
      throw new IllegalArgumentException("카카오 시연 자격 파일·본인 계정·앱·링크·시험 계정을 설정해 주세요.");
    }
  }
}
