package com.commercesettle.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfiguration {
  private static final Set<String> METHODS =
      Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

  @Bean
  RouteLocator apiRoutes(
      RouteLocatorBuilder builder,
      ObjectMapper mapper,
      @Value("classpath:contracts/openapi.json") Resource contract,
      @Value("${gateway.services.commerce}") String commerce,
      @Value("${gateway.services.payment}") String payment,
      @Value("${gateway.services.settlement}") String settlement)
      throws IOException {
    final var targets = Map.of("commerce", commerce, "payment", payment, "settlement", settlement);
    final var routes = builder.routes();
    try (var input = contract.getInputStream()) {
      final var paths = mapper.readTree(input).required("paths");
      for (var path : paths.properties()) {
        if (!path.getKey().startsWith("/api/")) {
          continue;
        }
        for (var operation : path.getValue().properties()) {
          if (!METHODS.contains(operation.getKey())) {
            continue;
          }
          final var owner = operation.getValue().required("x-owner-service").asText();
          final var target = targets.get(owner);
          if (target == null) {
            throw new IllegalStateException("Unsupported external API owner: " + owner);
          }
          final var id = operation.getValue().required("operationId").asText();
          final var method = HttpMethod.valueOf(operation.getKey().toUpperCase(Locale.ROOT));
          routes.route(
              "api-" + id,
              route -> route.method(method).and().path(false, path.getKey()).uri(target));
        }
      }
    }
    return routes.build();
  }

  @Bean
  HttpClientCustomizer disableTransportRetry() {
    return client -> client.disableRetry(true);
  }
}
