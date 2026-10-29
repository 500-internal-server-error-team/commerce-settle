package com.commercesettle.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;

/** 빌드에 포함한 OpenAPI의 외부 `/api/` 작업. 라우팅과 인증 판정이 같은 목록을 쓴다. */
public final class ApiOperations {
  private static final Set<String> METHODS =
      Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
  private static final Set<String> ACTORS = Set.of("PUBLIC", "BUYER", "SELLER");

  private final List<Operation> operations;
  private final Map<String, Operation> byRouteId;

  private ApiOperations(List<Operation> operations) {
    this.operations = List.copyOf(operations);
    this.byRouteId =
        operations.stream()
            .collect(Collectors.toUnmodifiableMap(Operation::routeId, Function.identity()));
  }

  static ApiOperations load(ObjectMapper mapper, Resource contract) throws IOException {
    try (var input = contract.getInputStream()) {
      final var paths = mapper.readTree(input).required("paths");
      final var operations = new ArrayList<Operation>();
      for (var path : paths.properties()) {
        if (!path.getKey().startsWith("/api/")) {
          continue;
        }
        for (var operation : path.getValue().properties()) {
          if (!METHODS.contains(operation.getKey())) {
            continue;
          }
          final var node = operation.getValue();
          final var actors =
              Arrays.stream(node.required("x-actor").asText().split("\\|"))
                  .map(String::trim)
                  .collect(Collectors.toUnmodifiableSet());
          if (!ACTORS.containsAll(actors) || (actors.contains("PUBLIC") && actors.size() > 1)) {
            throw new IllegalStateException("Unsupported external API actor: " + actors);
          }
          operations.add(
              new Operation(
                  "api-" + node.required("operationId").asText(),
                  HttpMethod.valueOf(operation.getKey().toUpperCase(Locale.ROOT)),
                  path.getKey(),
                  node.required("x-owner-service").asText(),
                  actors));
        }
      }
      return new ApiOperations(operations);
    }
  }

  /** 명세에 적힌 순서. 라우트 등록 순서를 바꾸지 않는다. */
  public List<Operation> all() {
    return operations;
  }

  public Optional<Operation> find(String routeId) {
    return Optional.ofNullable(byRouteId.get(routeId));
  }

  public record Operation(
      String routeId, HttpMethod method, String path, String owner, Set<String> actors) {
    public boolean isPublic() {
      return actors.contains("PUBLIC");
    }

    public boolean allows(String actorKind) {
      return !isPublic() && actors.contains(actorKind);
    }
  }
}
