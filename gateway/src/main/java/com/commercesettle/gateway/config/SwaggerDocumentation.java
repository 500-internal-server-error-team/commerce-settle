package com.commercesettle.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.Resource;

/** Gateway가 이미 사용하는 외부 API 권한을 Swagger 명세와 클라이언트에 연결한다. */
public final class SwaggerDocumentation {
  private final ObjectMapper mapper;
  private final ApiOperations operations;
  private final ObjectNode scheme;

  public SwaggerDocumentation(ObjectMapper mapper, ApiOperations operations, Resource contract)
      throws IOException {
    this.mapper = mapper;
    this.operations = operations;
    try (var input = contract.getInputStream()) {
      scheme =
          (ObjectNode)
              mapper
                  .readTree(input)
                  .required("components")
                  .required("securitySchemes")
                  .required("accountSession");
      scheme.put("description", "로그인하면 보호 API에 인증이 자동으로 연결됩니다.");
    }
  }

  public String enrich(String owner, String body) {
    if (body == null || body.isBlank()) return body;
    try {
      final var parsed = mapper.readTree(body);
      if (!(parsed instanceof ObjectNode document)
          || !document.has("openapi")
          || !document.path("paths").isObject()) return body;
      document.withObject("/components/securitySchemes").set("accountSession", scheme.deepCopy());
      for (var operation : operations.all()) {
        if (!operation.owner().equals(owner)) continue;
        final var node =
            document
                .path("paths")
                .path(operation.path())
                .path(operation.method().name().toLowerCase(java.util.Locale.ROOT));
        if (!(node instanceof ObjectNode method)) continue;
        method.put("x-actor", String.join("|", operation.actors().stream().sorted().toList()));
        final var security = mapper.createArrayNode();
        if (!operation.isPublic()) security.addObject().putArray("accountSession");
        method.set("security", security);
      }
      return mapper.writeValueAsString(document);
    } catch (IOException error) {
      throw new IllegalStateException("Unable to read the upstream OpenAPI document", error);
    }
  }

  public String clientConfiguration(boolean fixtures) throws IOException {
    final var result = new LinkedHashMap<String, Object>();
    result.put(
        "operations",
        operations.all().stream()
            .map(
                operation -> {
                  final var descriptor = new LinkedHashMap<String, Object>();
                  descriptor.put("method", operation.method().name());
                  descriptor.put("path", operation.path());
                  descriptor.put("actors", operation.actors().stream().sorted().toList());
                  return descriptor;
                })
            .toList());
    result.put(
        "login",
        Map.of(
            "BUYER", requiredPath("api-post_api_buyer_sessions"),
            "SELLER", requiredPath("api-post_api_seller_sessions")));
    result.put("logout", requiredPath("api-delete_api_sessions_current"));
    result.put("fixtures", fixtures);
    return mapper.writeValueAsString(result);
  }

  private String requiredPath(String routeId) {
    return operations
        .find(routeId)
        .orElseThrow(
            () -> new IllegalStateException("Missing Swagger session operation: " + routeId))
        .path();
  }
}
