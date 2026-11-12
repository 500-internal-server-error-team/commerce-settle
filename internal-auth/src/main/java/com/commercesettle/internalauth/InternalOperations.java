package com.commercesettle.internalauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 빌드에 포함한 OpenAPI의 모든 작업. 요청의 메서드·경로를 명세 작업과 대조한다. */
public final class InternalOperations {
  private static final Set<String> METHODS =
      Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

  private final List<Operation> operations;

  private InternalOperations(List<Operation> operations) {
    this.operations = List.copyOf(operations);
  }

  public static InternalOperations load(ObjectMapper mapper, InputStream contract)
      throws IOException {
    final var parser = new PathPatternParser();
    final var operations = new ArrayList<Operation>();
    for (var path : mapper.readTree(contract).required("paths").properties()) {
      final var pattern = parser.parse(path.getKey());
      for (var operation : path.getValue().properties()) {
        if (!METHODS.contains(operation.getKey())) {
          continue;
        }
        final var node = operation.getValue();
        operations.add(
            new Operation(
                operation.getKey().toUpperCase(Locale.ROOT),
                pattern,
                node.required("x-owner-service").asText(),
                Arrays.stream(node.required("x-actor").asText().split("\\|"))
                    .map(String::trim)
                    .collect(Collectors.toUnmodifiableSet())));
      }
    }
    return new InternalOperations(operations);
  }

  /** 메서드와 경로가 모두 맞는 작업. 같은 경로의 다른 메서드만 있으면 없음으로 본다. 여러 경로가 맞으면 더 구체적인 경로를 쓴다. */
  public Optional<Operation> find(String method, PathContainer path) {
    return operations.stream()
        .filter(operation -> operation.method().equals(method) && operation.path().matches(path))
        .min(Comparator.comparing(Operation::path, PathPattern.SPECIFICITY_COMPARATOR));
  }

  /**
   * @param actors 명세 {@code x-actor}의 호출 주체. 예: {@code BUYER}, {@code SERVICE:payment}, {@code
   *     OPERATOR}
   */
  public record Operation(String method, PathPattern path, String owner, Set<String> actors) {}
}
