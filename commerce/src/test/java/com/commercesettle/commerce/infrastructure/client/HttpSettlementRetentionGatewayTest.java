package com.commercesettle.commerce.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.port.SettlementRetentionGateway;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpSettlementRetentionGatewayTest {
  private static final String CREDENTIAL = "test-commerce-retention-credential-0123456789abcdef";
  private final UUID batch = UUID.randomUUID();
  private final UUID seller = UUID.randomUUID();
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private com.sun.net.httpserver.HttpServer server;
  private HttpSettlementRetentionGateway gateway;

  @BeforeEach
  void start() throws Exception {
    server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/settlement-retention",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    gateway =
        new HttpSettlementRetentionGateway(
            RestClient.create("http://127.0.0.1:" + server.getAddress().getPort()), CREDENTIAL);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("실제 HTTP에 전용 자격을 붙이고 명시적 null을 미종결로 해석한다")
  void recognizesExplicitNonterminalResponse() {
    body.set("[{\"batchId\":\"" + batch + "\",\"sellerId\":\"" + seller + "\",\"closedAt\":null}]");
    assertThat(gateway.lookup(Set.of(batch)))
        .containsExactly(new SettlementRetentionGateway.Closure(batch, seller, null));
    assertThat(authorization.get()).isEqualTo("Bearer " + CREDENTIAL);
  }

  @Test
  @DisplayName("종결 필드 누락을 미종결로 간주하지 않고 원천 미확인으로 거절한다")
  void rejectsMissingClosureField() {
    body.set("[{\"batchId\":\"" + batch + "\",\"sellerId\":\"" + seller + "\"}]");
    assertThatThrownBy(() -> gateway.lookup(Set.of(batch)))
        .isInstanceOf(SettlementRetentionGateway.UnavailableException.class);
  }

  @Test
  @DisplayName("한국 시각의 종결 응답을 마이크로초 손실 없이 원래 Instant로 변환한다")
  void readsKoreanOffsetWithoutPrecisionLoss() {
    body.set(
        "[{\"batchId\":\""
            + batch
            + "\",\"sellerId\":\""
            + seller
            + "\",\"closedAt\":\"2026-08-02T10:00:00.123456+09:00\"}]");
    assertThat(gateway.lookup(Set.of(batch)))
        .containsExactly(
            new SettlementRetentionGateway.Closure(
                batch, seller, java.time.Instant.parse("2026-08-02T01:00:00.123456Z")));
  }
}
