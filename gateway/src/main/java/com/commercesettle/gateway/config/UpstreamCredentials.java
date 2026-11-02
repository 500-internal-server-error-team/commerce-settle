package com.commercesettle.gateway.config;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 대상 서비스별 Gateway 내부 호출 자격(ADR 0010). 사용자 세션 토큰과 별개이며 해당 대상에만 유효하다.
 *
 * <p>값이 없거나 256비트(URL-safe 43자) 미만이면 기동하지 않는다. 원문은 로그·응답에 넣지 않는다.
 */
public final class UpstreamCredentials {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43,}");

  private final Map<String, String> authorizations;

  public UpstreamCredentials(Map<String, String> credentials) {
    credentials.forEach(
        (service, credential) -> {
          if (credential == null || !FORMAT.matcher(credential).matches()) {
            throw new IllegalStateException(
                "Gateway credential for " + service + " must be URL-safe and at least 43 chars");
          }
        });
    this.authorizations =
        credentials.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> "Bearer " + entry.getValue()));
  }

  /** 대상 서비스에 보낼 Authorization 값. 설정에 없는 대상이면 전달하지 않도록 예외를 던진다. */
  public String authorization(String service) {
    final var value = authorizations.get(service);
    if (value == null) {
      throw new IllegalStateException("No gateway credential for " + service);
    }
    return value;
  }
}
