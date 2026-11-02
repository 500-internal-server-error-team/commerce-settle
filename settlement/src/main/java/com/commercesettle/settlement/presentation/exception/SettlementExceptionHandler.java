package com.commercesettle.settlement.presentation.exception;

import com.commercesettle.settlement.domain.exception.PayoutNotFoundException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Settlement MVC 경계의 오류를 공통 오류 계약(application/problem+json, code, 실제 traceId)으로 변환한다. 커머스의 처리기와 같은
 * 형식이며, 내부 호출 자격 판정 실패(401·403·404)도 이 처리기를 거친다. 응답에는 거절된 입력 값·예외 메시지·내부 주소를 넣지 않는다.
 */
@RestControllerAdvice
public class SettlementExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(SettlementExceptionHandler.class);
  private static final String CODE = "code";
  private static final String INVALID_FORMAT = "형식이 올바르지 않습니다.";

  private final ObjectProvider<Tracer> tracer;

  public SettlementExceptionHandler(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Object> invalidArgument(IllegalArgumentException error, WebRequest request) {
    return respond(ErrorCode.COMMON_VALIDATION_FAILED, request);
  }

  @ExceptionHandler(PayoutNotFoundException.class)
  ResponseEntity<Object> payoutNotFound(PayoutNotFoundException e, WebRequest request) {
    return respond(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "지급을 찾을 수 없습니다.", null, request);
  }

  @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
  ResponseEntity<Object> temporarilyUnavailable(RuntimeException e, WebRequest request) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "settlement.request")
        .addKeyValue("errorCode", ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE.name())
        .addKeyValue("errorType", e.getClass().getName())
        .addKeyValue("traceId", traceId())
        .log("Storage is temporarily unavailable");
    return respond(ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE, "일시적으로 처리할 수 없습니다.", null, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    LOGGER
        .atError()
        .addKeyValue("event", "settlement.request")
        .addKeyValue("errorCode", ErrorCode.COMMON_INTERNAL_ERROR.name())
        .addKeyValue("errorType", e.getClass().getName())
        .addKeyValue("traceId", traceId())
        .log("Unexpected request failure");
    return respond(ErrorCode.COMMON_INTERNAL_ERROR, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    ex.getParameterValidationResults()
        .forEach(
            result -> {
              String name = parameterName(result.getMethodParameter());
              result.getResolvableErrors().stream()
                  .map(MessageSourceResolvable::getDefaultMessage)
                  .forEach(reason -> errors.add(error(name, reason)));
            });
    return invalid(errors, request);
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return invalid(List.of(error(ex.getParameterName(), "필수입니다.")), request);
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String field = ex.getPropertyName() == null ? "request" : ex.getPropertyName();
    return invalid(List.of(error(field, INVALID_FORMAT)), request);
  }

  /** 프레임워크 오류도 상태별 공통 코드와 고정 설명으로 바꿔 원문 메시지를 내보내지 않는다. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      org.springframework.web.bind.MethodArgumentNotValidException error,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ErrorCode code = ErrorCode.forStatus(status);
    var body = problem(status, code, code.detail(), request);
    body.setProperty(
        "errors",
        error.getBindingResult().getFieldErrors().stream()
            .map(
                field ->
                    java.util.Map.of(
                        "field", field.getField(), "reason", field.getDefaultMessage()))
            .toList());
    return ResponseEntity.status(status).headers(headers).body(body);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem
        && problem.getProperties() != null
        && problem.getProperties().containsKey(CODE)) {
      return ResponseEntity.status(status).headers(headers).body(problem);
    }
    ErrorCode code = ErrorCode.forStatus(status);
    return ResponseEntity.status(status)
        .headers(headers)
        .body(problem(status, code, code.detail(), request));
  }

  private ResponseEntity<Object> invalid(List<Map<String, String>> errors, WebRequest request) {
    ErrorCode code = ErrorCode.COMMON_VALIDATION_FAILED;
    return respond(code, code.detail(), errors.isEmpty() ? null : errors, request);
  }

  private ResponseEntity<Object> respond(ErrorCode code, WebRequest request) {
    return respond(code, code.detail(), null, request);
  }

  private ResponseEntity<Object> respond(
      ErrorCode code, String detail, List<Map<String, String>> errors, WebRequest request) {
    ProblemDetail problem = problem(code.status(), code, detail, request);
    if (errors != null) {
      problem.setProperty("errors", errors);
    }
    return ResponseEntity.status(code.status()).body(problem);
  }

  private ProblemDetail problem(
      HttpStatusCode status, ErrorCode code, String detail, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create(
            "urn:commerce-settle:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setTitle(title(status));
    if (request instanceof ServletWebRequest servlet) {
      problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
    }
    problem.setProperty(CODE, code.name());
    problem.setProperty("traceId", traceId());
    return problem;
  }

  private String traceId() {
    Tracer current = tracer.getIfAvailable(() -> Tracer.NOOP);
    Span span = current.currentSpan();
    return span == null ? "" : span.context().traceId();
  }

  private static String parameterName(MethodParameter parameter) {
    RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
    if (param != null && !param.value().isEmpty()) {
      return param.value();
    }
    String name = parameter.getParameterName();
    return name == null ? "request" : name;
  }

  private static Map<String, String> error(String field, String reason) {
    String name = field == null || field.isBlank() || field.length() > 100 ? "request" : field;
    return Map.of("field", name, "reason", reason == null ? INVALID_FORMAT : reason);
  }

  private static String title(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "잘못된 요청";
      case 401 -> "인증 필요";
      case 403 -> "접근 권한 없음";
      case 404 -> "대상 없음";
      case 409 -> "요청 충돌";
      case 503 -> "일시적 서비스 불가";
      default -> status.is4xxClientError() ? "요청 거절" : "서버 오류";
    };
  }
}
