package com.commercesettle.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.commercesettle.gateway.config.ApiOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

class SessionAuthenticationFilterTest {
  @Test
  @DisplayName("명세에 없는 라우트는 공개로 전달하지 않고 404로 거절한다")
  void rejectsRouteOutsideContract() {
    final var verifier = mock(SessionVerifier.class);
    final var chain = mock(GatewayFilterChain.class);
    final var filter = new SessionAuthenticationFilter(mock(ApiOperations.class), verifier);
    final var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/unregistered"));
    exchange
        .getAttributes()
        .put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async()
                .id("unregistered")
                .uri("http://localhost")
                .predicate(ignored -> true)
                .build());

    assertThatThrownBy(() -> filter.filter(exchange, chain).block())
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

    then(chain).shouldHaveNoInteractions();
    then(verifier).shouldHaveNoInteractions();
  }
}
