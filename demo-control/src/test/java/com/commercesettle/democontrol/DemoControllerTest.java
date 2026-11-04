package com.commercesettle.democontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.web.server.ResponseStatusException;

class DemoControllerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("헤더 뒤 본문이 멈추거나 조금씩 와도 전체 응답 시간 상한을 지킨다")
  void boundsTheWholeResponseAndRecoversAfterTimeout(boolean trickle) throws Exception {
    var release = new CountDownLatch(1);
    var headers = new CountDownLatch(1);
    var factory = new TomcatServletWebServerFactory(0);
    factory.setAddress(InetAddress.getByName("127.0.0.1"));
    var upstream =
        factory.getWebServer(
            context ->
                context
                    .addServlet(
                        "slow",
                        new HttpServlet() {
                          @Override
                          protected void doGet(
                              HttpServletRequest request, HttpServletResponse response)
                              throws IOException {
                            response.setContentType("application/json");
                            if (request.getRequestURI().equals("/api/products")) {
                              response.getWriter().write("{\"items\":[]}");
                              return;
                            }
                            response.flushBuffer();
                            headers.countDown();
                            try {
                              while (!release.await(200, TimeUnit.MILLISECONDS)) {
                                if (trickle) {
                                  response.getWriter().write(" ");
                                  response.flushBuffer();
                                }
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          }
                        })
                    .addMapping("/*"));
    upstream.start();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      String url = "http://127.0.0.1:" + upstream.getPort();
      var controller =
          new DemoController(
              new DemoSettings(
                  Path.of("."),
                  url,
                  Map.of("commerce", url),
                  Map.of("commerce", "synthetic"),
                  Map.of(),
                  false,
                  "commerce-settle-ui-load"),
              mapper);
      var request =
          executor.submit(
              () ->
                  controller.execute(
                      new DemoController.Command(
                          "get_internal_coupon_issue_runs", Map.of(), Map.of(), null, null),
                      null));
      assertThat(headers.await(3, TimeUnit.SECONDS)).isTrue();

      try {
        assertThatThrownBy(() -> request.get(12, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ResponseStatusException.class)
            .satisfies(
                error -> {
                  var cause = (ResponseStatusException) error.getCause();
                  assertThat(cause.getStatusCode().value()).isEqualTo(504);
                  assertThat(cause.getReason()).contains("재조회");
                });
      } finally {
        request.cancel(true);
        release.countDown();
      }
      assertThat(
              controller
                  .execute(
                      new DemoController.Command(
                          "get_api_products", Map.of(), Map.of(), null, null),
                      null)
                  .upstreamStatus())
          .isEqualTo(200);
    } finally {
      release.countDown();
      executor.shutdownNow();
      executor.awaitTermination(3, TimeUnit.SECONDS);
      upstream.stop();
    }
  }

  @Test
  @DisplayName("응답 본문 2MiB 상한을 지키고 다음 정상 요청을 처리한다")
  void boundsResponseBytesBeforeParsing() throws Exception {
    var factory = new TomcatServletWebServerFactory(0);
    factory.setAddress(InetAddress.getByName("127.0.0.1"));
    var upstream =
        factory.getWebServer(
            context ->
                context
                    .addServlet(
                        "large",
                        new HttpServlet() {
                          @Override
                          protected void doGet(
                              HttpServletRequest request, HttpServletResponse response)
                              throws IOException {
                            response.setContentType("application/json");
                            int size =
                                request.getRequestURI().equals("/api/orders")
                                    ? 2_097_153
                                    : 2_097_152;
                            response.getWriter().write("\"" + "a".repeat(size - 2) + "\"");
                          }
                        })
                    .addMapping("/*"));
    upstream.start();
    try {
      var controller =
          new DemoController(
              new DemoSettings(
                  Path.of("."),
                  "http://127.0.0.1:" + upstream.getPort(),
                  Map.of(),
                  Map.of(),
                  Map.of(),
                  false,
                  "commerce-settle-ui-load"),
              mapper);
      assertThatThrownBy(
              () ->
                  controller.execute(
                      new DemoController.Command("get_api_orders", Map.of(), Map.of(), null, null),
                      null))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
      assertThat(
              controller
                  .execute(
                      new DemoController.Command(
                          "get_api_products", Map.of(), Map.of(), null, null),
                      null)
                  .data()
                  .asText())
          .hasSize(2_097_150);
    } finally {
      upstream.stop();
    }
  }

  @Test
  void forwardsPublicSessionToGatewayAndKeepsOperatorCredentialOnServer() throws Exception {
    AtomicReference<String> credential = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    var factory = new TomcatServletWebServerFactory(0);
    factory.setAddress(InetAddress.getByName("127.0.0.1"));
    var upstream =
        factory.getWebServer(
            context ->
                context
                    .addServlet(
                        "upstream",
                        new HttpServlet() {
                          @Override
                          protected void doGet(
                              HttpServletRequest request, HttpServletResponse response)
                              throws IOException {
                            credential.set(request.getHeader("Authorization"));
                            path.set(
                                request.getRequestURI()
                                    + (request.getQueryString() == null
                                        ? ""
                                        : "?" + request.getQueryString()));
                            response.setContentType("application/json");
                            response.getWriter().write("{\"items\":[]}");
                          }
                        })
                    .addMapping("/*"));
    upstream.start();
    try {
      String url = "http://127.0.0.1:" + upstream.getPort();
      DemoController controller =
          new DemoController(
              new DemoSettings(
                  Path.of("."),
                  url,
                  Map.of("commerce", url),
                  Map.of("commerce", "operator-proof"),
                  Map.of(),
                  false,
                  "commerce-settle-ui-load"),
              mapper);
      var publicResult =
          controller.execute(
              new DemoController.Command(
                  "get_api_orders", Map.of(), Map.of("page", "0"), null, null),
              "Bearer buyer-session");
      assertThat(publicResult.upstreamStatus()).isEqualTo(200);
      assertThat(credential.get()).isEqualTo("Bearer buyer-session");
      assertThat(path.get()).isEqualTo("/api/orders?page=0");
      controller.execute(
          new DemoController.Command(
              "get_internal_coupon_issue_runs", Map.of(), Map.of(), null, null),
          "Bearer buyer-session");
      assertThat(credential.get()).isEqualTo("Bearer operator-proof");
      assertThat(mapper.writeValueAsString(controller.info()))
          .doesNotContain("operator-proof", "buyer-session");
      assertThatThrownBy(
              () ->
                  controller.execute(
                      new DemoController.Command(
                          "get_internal_orders_orderId_payment_context",
                          Map.of(),
                          Map.of(),
                          null,
                          null),
                      null))
          .isInstanceOf(ResponseStatusException.class);
    } finally {
      upstream.stop();
    }
  }

  @Test
  void rejectsPathTraversalAndUnknownParameters() {
    assertThatThrownBy(
            () ->
                DemoController.resolvePath(
                    "/api/orders/{orderId}", Map.of("orderId", "../internal")))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> DemoController.resolvePath("/api/orders", Map.of("url", "http://elsewhere")))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(DemoController.resolvePath("/api/orders/{orderId}", Map.of("orderId", "order-123")))
        .isEqualTo("/api/orders/order-123");
  }

  @Test
  void missingOperatorCredentialDoesNotFallBackToBuyerSession() throws Exception {
    var controller =
        new DemoController(
            new DemoSettings(
                Path.of("."),
                "http://127.0.0.1:1",
                Map.of("commerce", "http://127.0.0.1:1"),
                Map.of(),
                Map.of(),
                false,
                "commerce-settle-ui-load"),
            mapper);
    assertThatThrownBy(
            () ->
                controller.execute(
                    new DemoController.Command(
                        "get_internal_coupon_issue_runs", Map.of(), Map.of(), null, null),
                    "Bearer buyer"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
  }
}
