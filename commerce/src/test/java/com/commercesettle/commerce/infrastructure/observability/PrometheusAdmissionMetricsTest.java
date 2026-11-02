package com.commercesettle.commerce.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrometheusAdmissionMetricsTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("instant query의 scrape 시각 대신 같은 수집기의 원본 관측 시각을 결합한다")
  void pairsOnlyMatchingOriginalObservation() throws Exception {
    var result =
        PrometheusAdmissionMetrics.decode(
            MAPPER.readTree(
                vector(
                    sample("financial_queue_records", "queue", "commerce.outbox", "a", "10"),
                    timestamp("a", "1000"),
                    timestamp("b", "9000"))));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().value()).isEqualTo(10);
    assertThat(result.getFirst().observedAt()).isEqualTo(Instant.ofEpochSecond(1000));
    assertThat(
            PrometheusAdmissionMetrics.decode(
                MAPPER.readTree(
                    vector(
                        sample("financial_queue_records", "queue", "commerce.outbox", "a", "10"),
                        timestamp("b", "9000")))))
        .isEmpty();
  }

  @Test
  @DisplayName("여러 인스턴스의 같은 논리 대기열을 각각 보존해 상태 판정에서 중복 제거한다")
  void preservesReplicaIdentity() throws Exception {
    var result =
        PrometheusAdmissionMetrics.decode(
            MAPPER.readTree(
                vector(
                    sample("financial_queue_records", "queue", "commerce.outbox", "a", "40000"),
                    timestamp("a", "1000"),
                    sample("financial_queue_records", "queue", "commerce.outbox", "b", "40000"),
                    timestamp("b", "1000"))));

    assertThat(result).hasSize(2);
    assertThat(result.getFirst().key()).isEqualTo(result.getLast().key());
  }

  @ParameterizedTest
  @ValueSource(strings = {"NaN", "+Inf", "-1"})
  @DisplayName("잘못된 수치를 정상 0으로 바꾸지 않는다")
  void rejectsInvalidValues(String value) throws Exception {
    var json =
        MAPPER.readTree(
            vector(
                sample("financial_queue_records", "queue", "commerce.outbox", "a", value),
                timestamp("a", "1000")));

    assertThatThrownBy(() -> PrometheusAdmissionMetrics.decode(json))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("실제 HTTP 벡터를 한번에 조회하고 서버 오류와 응답 상한 초과를 거절한다")
  void boundsHttpResponseAndRejectsServerFailure() throws Exception {
    var body =
        new java.util.concurrent.atomic.AtomicReference<>(
            vector(
                sample("financial_queue_records", "queue", "commerce.outbox", "a", "10"),
                timestamp("a", "1000")));
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    try (var server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      server.setSoTimeout(5000);
      var worker =
          executor.submit(
              () -> {
                for (int index = 0; index < 3; index++) {
                  try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var input =
                        new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = input.readLine();
                    assertThat(request).startsWith("GET /api/v1/query?query=");
                    for (String header;
                        (header = input.readLine()) != null && !header.isEmpty(); ) {}
                    requests.incrementAndGet();
                    byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                    var output = socket.getOutputStream();
                    output.write(
                        ("HTTP/1.1 "
                                + status.get()
                                + " Result\r\nContent-Length: "
                                + bytes.length
                                + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(bytes);
                    output.flush();
                  } catch (java.io.IOException e) {
                    if (index != 2) throw new java.io.UncheckedIOException(e);
                  }
                }
              });
      try (var metrics =
          new PrometheusAdmissionMetrics("http://localhost:" + server.getLocalPort(), MAPPER)) {
        assertThat(metrics.read()).hasSize(1);
        assertThat(requests.get()).isEqualTo(1);
        status.set(503);
        assertThatThrownBy(metrics::read).isInstanceOf(IllegalStateException.class);
        status.set(200);
        body.set("x".repeat(2 * 1024 * 1024 + 1));
        assertThatThrownBy(metrics::read)
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Metrics response too large");
      }
      worker.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  private static String vector(String... samples) {
    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
        + String.join(",", samples)
        + "]}}";
  }

  private static String sample(
      String name, String label, String resource, String instance, String value) {
    return "{\"metric\":{\"__name__\":\"commerce_settle_"
        + name
        + "\",\""
        + label
        + "\":\""
        + resource
        + "\",\"instance\":\""
        + instance
        + "\"},\"value\":[99999,\""
        + value
        + "\"]}";
  }

  private static String timestamp(String instance, String value) {
    return "{\"metric\":{\"__name__\":\"commerce_settle_admission_observed_timestamp_seconds\","
        + "\"signal\":\"QUEUE_SIZE\",\"resource\":\"commerce.outbox\",\"instance\":\""
        + instance
        + "\"},\"value\":[99999,\""
        + value
        + "\"]}";
  }
}
