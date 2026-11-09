package com.commercesettle.gateway.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** 개발 fixture의 고정 계정만 기존 로그인 API로 연결한다. 비밀번호는 브라우저에 전달하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class SwaggerFixtureConfiguration {
  private static final Map<String, String> ACCOUNTS =
      Map.of(
          "BUYER_A", "fixture-buyer-a@example.test",
          "BUYER_B", "fixture-buyer-b@example.test",
          "SELLER_A", "fixture-seller-a@example.test",
          "SELLER_B", "fixture-seller-b@example.test");

  @Bean
  RouterFunction<ServerResponse> swaggerFixtureSessions(
      WebClient.Builder builder,
      UpstreamCredentials credentials,
      @Value("${gateway.services.commerce}") String commerce,
      @Value("${app.dev-fixtures.password:}") String password) {
    if (password.length() < 12 || password.length() > 128 || password.isBlank()) {
      throw new IllegalArgumentException("DEV_FIXTURE_PASSWORD must contain 12..128 characters");
    }
    final var client =
        builder
            .clone()
            .baseUrl(commerce)
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .disableRetry(true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                        .responseTimeout(Duration.ofSeconds(5))))
            .build();
    return RouterFunctions.route()
        .POST(
            "/swagger-ui/fixture-sessions",
            request -> {
              final var uri = request.uri();
              final var origin = uri.getScheme() + "://" + uri.getRawAuthority();
              if (!origin.equals(request.headers().firstHeader(HttpHeaders.ORIGIN))
                  || !request
                      .headers()
                      .contentType()
                      .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                      .isPresent()) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
              }
              return request
                  .bodyToMono(FixtureLogin.class)
                  .filter(login -> login.account() != null && ACCOUNTS.containsKey(login.account()))
                  .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST)))
                  .flatMap(
                      login ->
                          client
                              .post()
                              .uri(
                                  login.account().startsWith("BUYER_")
                                      ? "/api/buyer-sessions"
                                      : "/api/seller-sessions")
                              .header(
                                  HttpHeaders.AUTHORIZATION, credentials.authorization("commerce"))
                              .contentType(MediaType.APPLICATION_JSON)
                              .bodyValue(
                                  Map.of(
                                      "email", ACCOUNTS.get(login.account()), "password", password))
                              .exchangeToMono(SwaggerFixtureConfiguration::respond)
                              .timeout(Duration.ofSeconds(5)));
            })
        .build();
  }

  private static Mono<ServerResponse> respond(ClientResponse response) {
    return response
        .bodyToMono(byte[].class)
        .defaultIfEmpty(new byte[0])
        .flatMap(
            body -> {
              final var result =
                  ServerResponse.status(response.statusCode())
                      .contentType(
                          response.headers().contentType().orElse(MediaType.APPLICATION_JSON))
                      .header(HttpHeaders.CACHE_CONTROL, "no-store");
              final var retryAfter =
                  response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
              if (retryAfter != null) result.header(HttpHeaders.RETRY_AFTER, retryAfter);
              return result.bodyValue(body);
            });
  }

  record FixtureLogin(String account) {}
}
