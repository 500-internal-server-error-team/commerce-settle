package com.commercesettle.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfiguration {
  @Bean
  ApiOperations apiOperations(
      ObjectMapper mapper, @Value("classpath:contracts/openapi.json") Resource contract)
      throws IOException {
    return ApiOperations.load(mapper, contract);
  }

  @Bean
  RouteLocator apiRoutes(
      RouteLocatorBuilder builder,
      ApiOperations operations,
      @Value("${gateway.services.commerce}") String commerce,
      @Value("${gateway.services.payment}") String payment,
      @Value("${gateway.services.settlement}") String settlement) {
    final var targets = Map.of("commerce", commerce, "payment", payment, "settlement", settlement);
    final var routes = builder.routes();
    for (var operation : operations.all()) {
      final var target = targets.get(operation.owner());
      if (target == null) {
        throw new IllegalStateException("Unsupported external API owner: " + operation.owner());
      }
      routes.route(
          operation.routeId(),
          route ->
              route.method(operation.method()).and().path(false, operation.path()).uri(target));
    }
    return routes.build();
  }

  @Bean
  HttpClientCustomizer disableTransportRetry() {
    return client -> client.disableRetry(true);
  }
}
