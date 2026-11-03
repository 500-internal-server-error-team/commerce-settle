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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.web.server.ResponseStatusException;

class DemoControllerTest {
  private final ObjectMapper mapper = new ObjectMapper();

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
