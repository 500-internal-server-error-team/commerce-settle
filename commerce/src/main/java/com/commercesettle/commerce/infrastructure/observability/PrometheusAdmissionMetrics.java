package com.commercesettle.commerce.infrastructure.observability;

import com.commercesettle.commerce.application.port.AdmissionMetrics;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Key;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Sample;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Signal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 모든 신호와 수집기 원본 시각을 한 instant query로 읽는다. scrape 시각은 신선도 근거가 아니다. */
public final class PrometheusAdmissionMetrics implements AdmissionMetrics, AutoCloseable {
  private static final String PREFIX = "commerce_settle_";
  private static final String OBSERVED = PREFIX + "admission_observed_timestamp_seconds";
  private static final Map<String, Signal> NAMES =
      Map.of(
          PREFIX + "financial_queue_records", Signal.QUEUE_SIZE,
          PREFIX + "financial_queue_due_delay_seconds", Signal.DUE_AGE,
          PREFIX + "kafka_group_lag_records", Signal.LAG,
          PREFIX + "data_filesystem_usage_ratio", Signal.STORAGE);
  private final HttpClient client;
  private final URI query;
  private final ObjectMapper mapper;

  public PrometheusAdmissionMetrics(String baseUrl, ObjectMapper mapper) {
    URI base = URI.create(baseUrl);
    if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null) throw new IllegalArgumentException("Invalid Prometheus URL");
    String selector =
        "{__name__=~\"commerce_settle_(financial_queue_(records|due_delay_seconds)|kafka_group_lag_records|data_filesystem_usage_ratio|admission_observed_timestamp_seconds)\"}";
    query =
        URI.create(
            baseUrl.replaceAll("/+$", "")
                + "/api/v1/query?query="
                + URLEncoder.encode(selector, StandardCharsets.UTF_8));
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    this.mapper = mapper;
  }

  @Override
  public List<Sample> read() {
    var request = HttpRequest.newBuilder(query).timeout(Duration.ofSeconds(5)).GET().build();
    var pending = client.sendAsync(request, ignored -> new LimitedBody());
    try {
      var response = pending.get(5, TimeUnit.SECONDS);
      if (response.statusCode() != 200) throw new IllegalStateException("Metrics unavailable");
      return decode(mapper.readTree(response.body()));
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Metrics interrupted", e);
    } catch (Exception e) {
      pending.cancel(true);
      throw new IllegalStateException("Metrics unavailable", e);
    }
  }

  static List<Sample> decode(JsonNode root) {
    if (!"success".equals(root.path("status").asText())
        || !"vector".equals(root.path("data").path("resultType").asText())
        || !root.path("data").path("result").isArray()
        || root.hasNonNull("warnings"))
      throw new IllegalArgumentException("Invalid metrics response");
    Map<Replica, Double> values = new HashMap<>();
    Map<Replica, Instant> times = new HashMap<>();
    for (JsonNode row : root.path("data").path("result")) {
      var labels = row.path("metric");
      String name = labels.path("__name__").asText();
      boolean timestamp = OBSERVED.equals(name);
      Signal signal = timestamp ? Signal.valueOf(labels.path("signal").asText()) : NAMES.get(name);
      if (signal == null) continue;
      String label =
          switch (signal) {
            case QUEUE_SIZE, DUE_AGE -> "queue";
            case LAG -> "group";
            case STORAGE -> "store";
          };
      String resource = labels.path(timestamp ? "resource" : label).asText();
      Map<String, String> origin = new HashMap<>();
      labels
          .fields()
          .forEachRemaining(
              e -> {
                if (!List.of("__name__", "signal", "resource", label).contains(e.getKey()))
                  origin.put(e.getKey(), e.getValue().asText());
              });
      Replica replica = new Replica(new Key(signal, resource), Map.copyOf(origin));
      if (!row.path("value").isArray() || row.path("value").size() != 2)
        throw new IllegalArgumentException("Invalid metrics sample");
      double value = Double.parseDouble(row.path("value").get(1).asText());
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Invalid metrics value");
      if (timestamp) {
        if (value == 0) continue;
        Instant time = Instant.ofEpochMilli((long) (value * 1000));
        if (times.putIfAbsent(replica, time) != null)
          throw new IllegalArgumentException("Duplicate timestamp");
      } else if (values.putIfAbsent(replica, value) != null)
        throw new IllegalArgumentException("Duplicate metrics sample");
    }
    List<Sample> result = new ArrayList<>();
    values.forEach(
        (replica, value) -> {
          Instant observed = times.get(replica);
          if (observed != null) result.add(new Sample(replica.key(), value, observed));
        });
    return List.copyOf(result);
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private record Replica(Key key, Map<String, String> origin) {}

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private static final int LIMIT = 2 * 1024 * 1024;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > LIMIT - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new IllegalArgumentException("Metrics response too large"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
