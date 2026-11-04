package com.commercesettle.democontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 명세에 있는 공개 작업은 Gateway로, OPERATOR 작업은 설정된 소유 서비스로만 전달한다. */
@RestController
public class DemoController {
  private static final Set<String> METHODS = Set.of("get", "post", "put", "delete");
  private static final Pattern PARAMETER = Pattern.compile("\\{([^}]+)}");
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
  private final Map<String, Operation> operations;
  private final DemoSettings settings;
  private final ObjectMapper mapper;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  DemoController(DemoSettings settings, ObjectMapper mapper) throws IOException {
    this.settings = settings;
    this.mapper = mapper;
    this.operations = new HashMap<>();
    try (var input = new ClassPathResource("contract/openapi.json").getInputStream()) {
      JsonNode contract = mapper.readTree(input);
      contract
          .required("paths")
          .properties()
          .forEach(
              path ->
                  path.getValue()
                      .properties()
                      .forEach(
                          method -> {
                            JsonNode node = method.getValue();
                            boolean operator = node.path("x-actor").asText().equals("OPERATOR");
                            if (METHODS.contains(method.getKey())
                                && (path.getKey().startsWith("/api/") || operator)) {
                              Set<String> query = new HashSet<>();
                              node.path("parameters")
                                  .forEach(
                                      parameter -> {
                                        JsonNode p =
                                            parameter.has("$ref")
                                                ? contract.at(
                                                    parameter
                                                        .required("$ref")
                                                        .asText()
                                                        .substring(1))
                                                : parameter;
                                        if (p.path("in").asText().equals("query"))
                                          query.add(p.path("name").asText());
                                      });
                              operations.put(
                                  node.required("operationId").asText(),
                                  new Operation(
                                      method.getKey().toUpperCase(java.util.Locale.ROOT),
                                      path.getKey(),
                                      node.required("x-owner-service").asText(),
                                      operator,
                                      query));
                            }
                          }));
    }
  }

  public record Command(
      @NotBlank String operationId,
      Map<String, String> pathParameters,
      Map<String, String> query,
      JsonNode body,
      String idempotencyKey) {}

  public record Result(int upstreamStatus, JsonNode data) {}

  private record Operation(
      String method, String path, String owner, boolean operator, Set<String> query) {}

  @GetMapping("/demo/info")
  public Map<String, Object> info() {
    return Map.of(
        "gateway",
        settings.gateway(),
        "operatorServices",
        settings.operatorCredentials().entrySet().stream()
            .filter(entry -> !entry.getValue().isBlank())
            .map(Map.Entry::getKey)
            .toList(),
        "timeTravel",
        false,
        "loadEnabled",
        settings.loadEnabled(),
        "loadProject",
        settings.loadProject(),
        "links",
        settings.monitoringLinks().entrySet().stream()
            .map(entry -> Map.of("title", entry.getKey(), "url", entry.getValue()))
            .toList());
  }

  @PostMapping("/demo/requests")
  public Result execute(
      @Valid @RequestBody Command command,
      @RequestHeader(value = "Authorization", required = false) String userAuthorization) {
    Operation operation = operations.get(command.operationId());
    if (operation == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 작업입니다.");
    String target =
        operation.operator() ? settings.services().get(operation.owner()) : settings.gateway();
    String authorization = userAuthorization;
    if (operation.operator()) {
      String credential = settings.operatorCredentials().get(operation.owner());
      if (credential == null || credential.isBlank())
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "소유 서비스의 운영자 자격이 설정되지 않았습니다.");
      authorization = "Bearer " + credential;
    }
    try {
      URI base = URI.create(target);
      if (!Set.of("http", "https").contains(base.getScheme())
          || base.getHost() == null
          || base.getUserInfo() != null
          || base.getRawQuery() != null
          || base.getRawFragment() != null
          || !Set.of("", "/").contains(base.getPath())) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서버 목적지 설정을 확인하세요.");
      }
      String path = resolvePath(operation.path(), command.pathParameters());
      String query = query(operation.query(), command.query());
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(target.replaceAll("/$", "") + path + query))
              .timeout(RESPONSE_TIMEOUT)
              .header("Content-Type", "application/json");
      if (authorization != null && !authorization.isBlank())
        request.header("Authorization", authorization);
      if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
        if (!command.idempotencyKey().matches("[\\x21-\\x7e]{8,128}"))
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "멱등키 형식을 확인하세요.");
        request.header("Idempotency-Key", command.idempotencyKey());
      }
      String body =
          command.body() == null || command.body().isNull()
              ? ""
              : mapper.writeValueAsString(command.body());
      request.method(
          operation.method(),
          body.isEmpty()
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      var subscriber = new BoundedBodySubscriber();
      var pending = client.sendAsync(request.build(), ignored -> subscriber);
      try {
        var response = pending.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        byte[] bytes = response.body();
        return new Result(
            response.statusCode(),
            bytes.length == 0 ? NullNode.getInstance() : mapper.readTree(bytes));
      } catch (TimeoutException e) {
        throw new ResponseStatusException(
            HttpStatus.GATEWAY_TIMEOUT, "응답 확인 시간을 초과했습니다. 원래 거래를 재조회하세요.");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof ResponseStatusException rejection) throw rejection;
        if (e.getCause() instanceof java.net.http.HttpTimeoutException)
          throw new ResponseStatusException(
              HttpStatus.GATEWAY_TIMEOUT, "응답 확인 시간을 초과했습니다. 원래 거래를 재조회하세요.");
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "응답을 확인하지 못했습니다. 원래 거래를 재조회하세요.");
      } finally {
        // The JDK request timer can finish at headers. Cancel the full body subscription too.
        subscriber.cancel();
        pending.cancel(true);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "응답 확인이 중단됐습니다. 원거래를 재조회하세요.");
    } catch (IOException | IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "응답을 확인하지 못했습니다. 원거래를 재조회하세요.");
    }
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long received;
    private boolean cancelled;

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription next) {
      if (cancelled) next.cancel();
      else {
        subscription = next;
        delegate.onSubscribe(next);
      }
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
      if (cancelled) return;
      for (ByteBuffer buffer : buffers) received += buffer.remaining();
      if (received > 2_097_152) {
        cancel();
        delegate.onError(
            new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "응답 크기가 상한을 초과했습니다. 원래 거래를 재조회하세요."));
      } else delegate.onNext(buffers);
    }

    @Override
    public synchronized void onError(Throwable error) {
      delegate.onError(error);
    }

    @Override
    public synchronized void onComplete() {
      delegate.onComplete();
    }

    synchronized void cancel() {
      cancelled = true;
      if (subscription != null) subscription.cancel();
    }
  }

  static String resolvePath(String template, Map<String, String> values) {
    var matcher = PARAMETER.matcher(template);
    Set<String> expected = new HashSet<>();
    while (matcher.find()) expected.add(matcher.group(1));
    Map<String, String> supplied = values == null ? Map.of() : values;
    if (!supplied.keySet().equals(expected))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "경로 입력이 명세와 다릅니다.");
    String result = template;
    for (String name : expected) {
      String value = supplied.get(name);
      if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "경로 식별자 형식을 확인하세요.");
      result = result.replace("{" + name + "}", value);
    }
    return result;
  }

  private static String query(Set<String> allowed, Map<String, String> values) {
    if (values == null) return "";
    if (!allowed.containsAll(values.keySet()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 조회 조건입니다.");
    return values.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isBlank())
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(
            java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.joining("&"), q -> q.isEmpty() ? "" : "?" + q));
  }
}
