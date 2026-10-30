package com.commercesettle.financemock.presentation.exception;

import com.commercesettle.financemock.application.exception.IdempotencyConflictException;
import com.commercesettle.financemock.application.exception.LookupFailedException;
import com.commercesettle.financemock.application.exception.PayoutAccountNotFoundException;
import com.commercesettle.financemock.application.exception.ScenarioUnavailableException;
import com.commercesettle.financemock.application.exception.TransactionNotFoundException;
import com.commercesettle.financemock.domain.exception.InsufficientMockFundsException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 대역 MVC 경계의 오류를 공통 오류 계약(application/problem+json, code, traceId)으로 바꾼다. 조회 장애·시나리오 부재(503)와 거래
 * 부재(404)를 구분하고, 응답에 입력 원문·예외 메시지를 넣지 않는다.
 */
@RestControllerAdvice
public class FinanceMockExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(FinanceMockExceptionHandler.class);
  private static final String CODE = "code";

  private final ObjectProvider<Tracer> tracer;

  public FinanceMockExceptionHandler(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<Object> idempotencyConflict(IdempotencyConflictException e, WebRequest request) {
    return respond(ErrorCode.COMMON_IDEMPOTENCY_CONFLICT, null, request);
  }

  @ExceptionHandler(TransactionNotFoundException.class)
  ResponseEntity<Object> transactionNotFound(TransactionNotFoundException e, WebRequest request) {
    return respond(ErrorCode.MOCK_TRANSACTION_NOT_FOUND, null, request);
  }

  @ExceptionHandler(ScenarioUnavailableException.class)
  ResponseEntity<Object> scenarioUnavailable(ScenarioUnavailableException e, WebRequest request) {
    return respond(ErrorCode.MOCK_SCENARIO_UNAVAILABLE, null, request);
  }

  @ExceptionHandler(LookupFailedException.class)
  ResponseEntity<Object> lookupFailed(LookupFailedException e, WebRequest request) {
    return respond(ErrorCode.MOCK_LOOKUP_FAILED, null, request);
  }

  @ExceptionHandler(PayoutAccountNotFoundException.class)
  ResponseEntity<Object> payoutAccountNotFound(
      PayoutAccountNotFoundException e, WebRequest request) {
    return respond(
        ErrorCode.COMMON_VALIDATION_FAILED,
        List.of(error("accountRef", "등록된 지급 재원 계정이 아닙니다.")),
        request);
  }

  /** 재원은 시험 전에 항상 충분하게 준비한다는 전제가 깨졌다. 지급 거절이 아니라 시험 준비 오류로 남긴다. */
  @ExceptionHandler(InsufficientMockFundsException.class)
  ResponseEntity<Object> fundsNotPrepared(InsufficientMockFundsException e, WebRequest request) {
    LOGGER
        .atError()
        .addKeyValue("event", "mock.payout")
        .addKeyValue("errorCode", ErrorCode.MOCK_FUNDS_NOT_PREPARED.name())
        .log("Payout funding account is not prepared");
    return respond(ErrorCode.MOCK_FUNDS_NOT_PREPARED, null, request);
  }

  @ExceptionHandler({TransientDataAccessException.class, CannotCreateTransactionException.class})
  ResponseEntity<Object> temporarilyUnavailable(RuntimeException e, WebRequest request) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "mock.request")
        .addKeyValue("result", "UNKNOWN")
        .addKeyValue("errorType", e.getClass().getName())
        .log("Storage is temporarily unavailable");
    return respond(ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE, null, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    LOGGER
        .atError()
        .addKeyValue("event", "mock.request")
        .addKeyValue("errorCode", ErrorCode.COMMON_INTERNAL_ERROR.name())
        .addKeyValue("errorType", e.getClass().getName())
        .log("Unexpected request failure");
    return respond(ErrorCode.COMMON_INTERNAL_ERROR, null, request);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        ex.getFieldErrors().stream()
            .map(field -> error(field.getField(), field.getDefaultMessage()))
            .toList();
    return respond(ErrorCode.COMMON_VALIDATION_FAILED, errors, request);
  }

  /** 헤더·쿼리에 제약이 있는 메서드는 본문 검증 오류도 이 경로로 온다. 본문은 필드 이름, 나머지는 파라미터 이름으로 알린다. */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        parameterErrors
            .getFieldErrors()
            .forEach(field -> errors.add(error(field.getField(), field.getDefaultMessage())));
        continue;
      }
      String name = parameterName(result.getMethodParameter());
      result
          .getResolvableErrors()
          .forEach(resolvable -> errors.add(error(name, resolvable.getDefaultMessage())));
    }
    return respond(ErrorCode.COMMON_VALIDATION_FAILED, errors, request);
  }

  /** 프레임워크 오류도 상태별 공통 코드와 고정 설명으로 바꿔 원문 메시지를 내보내지 않는다. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ErrorCode code = ErrorCode.forStatus(status);
    return ResponseEntity.status(status)
        .headers(headers)
        .body(problem(status, code, null, request));
  }

  private ResponseEntity<Object> respond(
      ErrorCode code, List<Map<String, String>> errors, WebRequest request) {
    return ResponseEntity.status(code.status()).body(problem(code.status(), code, errors, request));
  }

  private ProblemDetail problem(
      HttpStatusCode status, ErrorCode code, List<Map<String, String>> errors, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, code.detail());
    problem.setType(
        URI.create(
            "urn:commerce-settle:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    if (request instanceof ServletWebRequest servlet) {
      problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
    }
    problem.setProperty(CODE, code.name());
    problem.setProperty("traceId", traceId());
    if (errors != null && !errors.isEmpty()) {
      problem.setProperty("errors", errors);
    }
    return problem;
  }

  private String traceId() {
    Tracer current = tracer.getIfAvailable(() -> Tracer.NOOP);
    Span span = current.currentSpan();
    return span == null ? "" : span.context().traceId();
  }

  private static String parameterName(MethodParameter parameter) {
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    if (header != null && !header.value().isEmpty()) {
      return header.value();
    }
    String name = parameter.getParameterName();
    return name == null ? "request" : name;
  }

  private static Map<String, String> error(String field, String reason) {
    return Map.of("field", field, "reason", reason == null ? "형식이 올바르지 않습니다." : reason);
  }
}
