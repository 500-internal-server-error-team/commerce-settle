package com.commercesettle.gateway.filter;

import com.commercesettle.gateway.config.ApiOperations;
import com.commercesettle.gateway.exception.AuthenticationUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 명세의 {@code x-actor}로 보호 작업을 가려 세션을 검증하고, 검증한 신원만 백엔드에 전달한다.
 *
 * <p>클라이언트가 보낸 {@code X-Authenticated-*}와 {@code Authorization}은 공개 작업을 포함한 모든 요청에서 제거한다. 백엔드용 내부
 * 호출 자격은 아직 넣지 않는다.
 */
@Component
public class SessionAuthenticationFilter implements GlobalFilter, Ordered {
  static final String ACTOR_KIND = "X-Authenticated-Actor-Kind";
  static final String ACTOR_ID = "X-Authenticated-Actor-Id";
  static final String SESSION_HASH = "X-Authenticated-Session-Hash";
  private static final String IDENTITY_PREFIX = "x-authenticated-";
  private static final String LOGOUT_ROUTE = "api-delete_api_sessions_current";
  // application.yml의 서비스 명세 라우트. 그 밖의 라우트는 OpenAPI 작업이어야 한다.
  private static final Set<String> DOCUMENT_ROUTES =
      Set.of("commerce-docs", "payment-docs", "settlement-docs");
  private static final String BEARER = "bearer ";

  private final ApiOperations operations;
  private final SessionVerifier verifier;

  public SessionAuthenticationFilter(ApiOperations operations, SessionVerifier verifier) {
    this.operations = operations;
    this.verifier = verifier;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route != null && DOCUMENT_ROUTES.contains(route.getId())) {
      return chain.filter(forward(exchange, null, null));
    }
    // 명세에 없는 라우트는 공개로 간주하지 않는다.
    final var operation = route == null ? null : operations.find(route.getId()).orElse(null);
    if (operation == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    if (operation.isPublic()) {
      return chain.filter(forward(exchange, null, null));
    }
    final var token = bearerToken(exchange.getRequest().getHeaders());
    if (token == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    final var sessionHash =
        HexFormat.of()
            .formatHex(SessionVerifier.digest("SHA-256", token.getBytes(StandardCharsets.UTF_8)));
    return verifier
        .verify(sessionHash)
        .flatMap(
            result ->
                switch (result.outcome()) {
                  case UNAUTHENTICATED ->
                      Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                  case UNAVAILABLE -> Mono.error(new AuthenticationUnavailableException());
                  case AUTHENTICATED ->
                      operation.allows(result.actorKind())
                          ? chain.filter(
                              forward(
                                  exchange,
                                  result,
                                  LOGOUT_ROUTE.equals(operation.routeId()) ? sessionHash : null))
                          : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                });
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1000;
  }

  private static ServerWebExchange forward(
      ServerWebExchange exchange, SessionVerification identity, String sessionHash) {
    return exchange
        .mutate()
        .request(
            request ->
                request.headers(
                    headers -> {
                      headers.remove(HttpHeaders.AUTHORIZATION);
                      List.copyOf(headers.keySet()).stream()
                          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(IDENTITY_PREFIX))
                          .forEach(headers::remove);
                      if (identity != null) {
                        headers.set(ACTOR_KIND, identity.actorKind());
                        headers.set(ACTOR_ID, identity.actorId().toString());
                      }
                      if (sessionHash != null) {
                        headers.set(SESSION_HASH, sessionHash);
                      }
                    }))
        .build();
  }

  private static String bearerToken(HttpHeaders headers) {
    final var values = headers.get(HttpHeaders.AUTHORIZATION);
    if (values == null || values.size() != 1) {
      return null;
    }
    final var value = values.get(0);
    if (!value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    final var token = value.substring(BEARER.length());
    return token.isEmpty() || token.chars().anyMatch(Character::isWhitespace) ? null : token;
  }
}
