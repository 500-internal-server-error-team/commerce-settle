package com.commercesettle.settlement.presentation.exception;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * MVC 경계의 오류를 공통 오류 계약(application/problem+json, code, traceId)으로 바꾼다. 내부 호출 자격 판정 실패(401·403·404)도
 * 이 처리기를 거친다. 응답에는 입력 원문·예외 메시지를 넣지 않는다.
 */
@RestControllerAdvice
public class SettlementExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(SettlementExceptionHandler.class);

  private final ObjectProvider<Tracer> tracer;

  public SettlementExceptionHandler(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    LOGGER
        .atError()
        .addKeyValue("event", "settlement.request")
        .addKeyValue("errorCode", ErrorCode.COMMON_INTERNAL_ERROR.name())
        .addKeyValue("errorType", e.getClass().getName())
        .log("Unexpected request failure");
    ErrorCode code = ErrorCode.COMMON_INTERNAL_ERROR;
    return ResponseEntity.status(code.status()).body(problem(code.status(), code, request));
  }

  /** 프레임워크 오류도 상태별 공통 코드와 고정 설명으로 바꿔 원문 메시지를 내보내지 않는다. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return ResponseEntity.status(status)
        .headers(headers)
        .body(problem(status, ErrorCode.forStatus(status), request));
  }

  private ProblemDetail problem(HttpStatusCode status, ErrorCode code, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, code.detail());
    problem.setType(
        URI.create(
            "urn:commerce-settle:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    if (request instanceof ServletWebRequest servlet) {
      problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
    }
    problem.setProperty("code", code.name());
    problem.setProperty("traceId", traceId());
    return problem;
  }

  private String traceId() {
    Tracer current = tracer.getIfAvailable(() -> Tracer.NOOP);
    Span span = current.currentSpan();
    return span == null ? "" : span.context().traceId();
  }
}
