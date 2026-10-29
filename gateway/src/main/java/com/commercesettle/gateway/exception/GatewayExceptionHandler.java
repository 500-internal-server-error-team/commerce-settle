package com.commercesettle.gateway.exception;

import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayExceptionHandler.class);
  private final Tracer tracer;

  private final ServerResponse.Context responseContext;

  public GatewayExceptionHandler(ServerCodecConfigurer codecs, Tracer tracer) {
    this.tracer = tracer;
    this.responseContext =
        new ServerResponse.Context() {
          @Override
          public List<HttpMessageWriter<?>> messageWriters() {
            return codecs.getWriters();
          }

          @Override
          public List<ViewResolver> viewResolvers() {
            return List.of();
          }
        };
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(error);
    }
    return render(exchange, error)
        .flatMap(
            response -> {
              exchange
                  .getResponse()
                  .getHeaders()
                  .setContentType(MediaType.APPLICATION_PROBLEM_JSON);
              return response.writeTo(exchange, responseContext);
            });
  }

  private Mono<ServerResponse> render(ServerWebExchange exchange, Throwable error) {
    return Mono.defer(
        () -> {
          final var status = status(error);
          final var code = code(status);
          final var currentSpan = tracer.currentSpan();
          final var ownSpan =
              currentSpan == null ? tracer.nextSpan().name("gateway.error").start() : null;
          final var traceId = (currentSpan == null ? ownSpan : currentSpan).context().traceId();
          final var notForwarded = error instanceof AuthenticationUnavailableException;
          final var problem =
              ProblemDetail.forStatusAndDetail(
                  status,
                  notForwarded
                      ? "인증 상태를 확인할 수 없어 요청을 전달하지 않았습니다. 잠시 후 다시 시도해 주세요."
                      : detail(status));
          problem.setType(
              URI.create(
                  "urn:commerce-settle:error:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
          problem.setTitle(title(status));
          problem.setInstance(URI.create(exchange.getRequest().getURI().getRawPath()));
          problem.setProperty("code", code);
          problem.setProperty("traceId", traceId);
          if (status.is5xxServerError()) {
            final var event = status.value() == 503 ? LOGGER.atWarn() : LOGGER.atError();
            if (status.value() == 503 && !notForwarded) {
              event.addKeyValue("result", "UNKNOWN");
            }
            event
                .addKeyValue("event", "gateway.request")
                .addKeyValue("errorCode", code)
                .addKeyValue("traceId", traceId)
                .addKeyValue("httpStatus", status.value())
                .addKeyValue("errorType", error.getClass().getName())
                .log("Gateway request failed");
          }
          final var response =
              ServerResponse.status(status)
                  .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                  .bodyValue(problem);
          return ownSpan == null ? response : response.doFinally(signal -> ownSpan.end());
        });
  }

  private static HttpStatusCode status(Throwable error) {
    if (error instanceof ErrorResponse response) {
      final var status = response.getStatusCode();
      return status.value() == 502 || status.value() == 504
          ? HttpStatus.SERVICE_UNAVAILABLE
          : status;
    }
    for (var cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof IOException
          || cause instanceof reactor.netty.channel.AbortedException
          || cause instanceof TimeoutException
          || cause instanceof io.netty.handler.timeout.TimeoutException) {
        return HttpStatus.SERVICE_UNAVAILABLE;
      }
      if (cause == cause.getCause()) {
        break;
      }
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static String code(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "COMMON_VALIDATION_FAILED";
      case 401 -> "COMMON_AUTHENTICATION_REQUIRED";
      case 403 -> "COMMON_ROLE_FORBIDDEN";
      case 404 -> "COMMON_RESOURCE_NOT_FOUND";
      case 405 -> "COMMON_METHOD_NOT_ALLOWED";
      case 406 -> "COMMON_NOT_ACCEPTABLE";
      case 415 -> "COMMON_UNSUPPORTED_MEDIA_TYPE";
      case 429 -> "COMMON_RATE_LIMITED";
      case 503 -> "COMMON_TEMPORARILY_UNAVAILABLE";
      default -> "COMMON_INTERNAL_ERROR";
    };
  }

  private static String title(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "잘못된 요청";
      case 401 -> "인증 필요";
      case 403 -> "접근 권한 없음";
      case 404 -> "대상 없음";
      case 503 -> "일시적 서비스 불가";
      default -> status.is4xxClientError() ? "요청 거절" : "서버 오류";
    };
  }

  private static String detail(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "요청 형식과 값을 확인해 주세요.";
      case 401 -> "로그인이 필요합니다.";
      case 403 -> "이 요청을 수행할 권한이 없습니다.";
      case 404 -> "요청한 경로를 찾을 수 없습니다.";
      case 503 -> "연결된 서비스에 요청을 전달할 수 없습니다. 처리 결과는 확인이 필요합니다.";
      default -> "요청을 처리할 수 없습니다.";
    };
  }
}
