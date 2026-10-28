package com.commercesettle.commerce.presentation.exception;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 커머스 MVC 경계의 오류를 공통 오류 계약(application/problem+json, code, 실제 traceId)으로 변환한다. 응답에는 거절된 입력 값·예외
 * 메시지·내부 주소를 넣지 않는다.
 */
@RestControllerAdvice
public class CommerceExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(CommerceExceptionHandler.class);
  private static final String CODE = "code";
  private static final String ERRORS = "errors";
  private static final String INVALID_FORMAT = "형식이 올바르지 않습니다.";

  private final ObjectProvider<Tracer> tracer;

  public CommerceExceptionHandler(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @ExceptionHandler(ProductNotFoundException.class)
  ResponseEntity<Object> productNotFound(ProductNotFoundException e, WebRequest request) {
    return respond(
        HttpStatus.NOT_FOUND, "COMMON_RESOURCE_NOT_FOUND", "상품을 찾을 수 없습니다.", null, request);
  }

  @ExceptionHandler({VersionConflictException.class, OptimisticLockingFailureException.class})
  ResponseEntity<Object> versionConflict(RuntimeException e, WebRequest request) {
    return respond(
        HttpStatus.CONFLICT,
        "COMMON_VERSION_CONFLICT",
        "다른 변경이 먼저 반영됐습니다. 최신 상태를 조회한 뒤 다시 요청해 주세요.",
        null,
        request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<Object> idempotencyConflict(IdempotencyConflictException e, WebRequest request) {
    return respond(
        HttpStatus.CONFLICT,
        "COMMON_IDEMPOTENCY_CONFLICT",
        "같은 멱등키의 다른 요청이 있거나 처리 중입니다. 같은 본문이면 잠시 후 다시 요청해 주세요.",
        null,
        request);
  }

  @ExceptionHandler(InsufficientInventoryException.class)
  ResponseEntity<Object> insufficientInventory(
      InsufficientInventoryException e, WebRequest request) {
    return respond(
        HttpStatus.CONFLICT, "INVENTORY_INSUFFICIENT", "가용 재고보다 많이 줄일 수 없습니다.", null, request);
  }

  @ExceptionHandler({TransientDataAccessException.class, CannotCreateTransactionException.class})
  ResponseEntity<Object> temporarilyUnavailable(RuntimeException e, WebRequest request) {
    String traceId = traceId();
    LOGGER
        .atWarn()
        .addKeyValue("event", "commerce.request")
        .addKeyValue("result", "UNKNOWN")
        .addKeyValue("errorCode", "COMMON_TEMPORARILY_UNAVAILABLE")
        .addKeyValue("errorType", e.getClass().getName())
        .addKeyValue("traceId", traceId)
        .log("Storage is temporarily unavailable");
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMMON_TEMPORARILY_UNAVAILABLE",
        "일시적으로 처리할 수 없습니다. 같은 멱등키로 결과를 다시 확인해 주세요.",
        null,
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    LOGGER
        .atError()
        .addKeyValue("event", "commerce.request")
        .addKeyValue("errorCode", "COMMON_INTERNAL_ERROR")
        .addKeyValue("errorType", e.getClass().getName())
        .addKeyValue("traceId", traceId())
        .log("Unexpected request failure");
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "COMMON_INTERNAL_ERROR",
        "요청을 처리하지 못했습니다.",
        null,
        request);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        ex.getFieldErrors().stream().map(CommerceExceptionHandler::fieldError).toList();
    return invalid(errors, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return invalid(validationErrors(ex), request);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
      String reason =
          mapping instanceof UnrecognizedPropertyException ? "허용하지 않는 필드입니다." : INVALID_FORMAT;
      errors.add(error(jsonPath(mapping), reason));
    }
    return invalid(errors, request);
  }

  @Override
  protected ResponseEntity<Object> handleServletRequestBindingException(
      ServletRequestBindingException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (ex instanceof MissingRequestHeaderException missing) {
      return invalid(List.of(error(missing.getHeaderName(), "필수입니다.")), request);
    }
    return super.handleServletRequestBindingException(ex, headers, status, request);
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
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem
        && problem.getProperties() != null
        && problem.getProperties().containsKey(CODE)) {
      return ResponseEntity.status(status).headers(headers).body(problem);
    }
    ProblemDetail problem = problem(status, commonCode(status), commonDetail(status), request);
    return ResponseEntity.status(status).headers(headers).body(problem);
  }

  private ResponseEntity<Object> invalid(List<Map<String, String>> errors, WebRequest request) {
    return respond(
        HttpStatus.BAD_REQUEST,
        "COMMON_VALIDATION_FAILED",
        "입력값을 확인해 주세요.",
        errors.isEmpty() ? null : errors,
        request);
  }

  private ResponseEntity<Object> respond(
      HttpStatus status,
      String code,
      String detail,
      List<Map<String, String>> errors,
      WebRequest request) {
    ProblemDetail problem = problem(status, code, detail, request);
    if (errors != null) {
      problem.setProperty(ERRORS, errors);
    }
    return ResponseEntity.status(status).body(problem);
  }

  private ProblemDetail problem(
      HttpStatusCode status, String code, String detail, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create("urn:commerce-settle:error:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setTitle(title(status));
    if (request instanceof ServletWebRequest servlet) {
      problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
    }
    problem.setProperty(CODE, code);
    problem.setProperty("traceId", traceId());
    return problem;
  }

  private String traceId() {
    Tracer current = tracer.getIfAvailable(() -> Tracer.NOOP);
    Span span = current.currentSpan();
    return span == null ? "" : span.context().traceId();
  }

  private static List<Map<String, String>> validationErrors(MethodValidationResult result) {
    List<Map<String, String>> errors = new ArrayList<>();
    result
        .getParameterValidationResults()
        .forEach(
            parameterResult -> {
              if (parameterResult instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().stream()
                    .map(CommerceExceptionHandler::fieldError)
                    .forEach(errors::add);
                return;
              }
              String name = parameterName(parameterResult.getMethodParameter());
              parameterResult.getResolvableErrors().stream()
                  .map(MessageSourceResolvable::getDefaultMessage)
                  .forEach(reason -> errors.add(error(name, reason)));
            });
    return errors;
  }

  private static String parameterName(MethodParameter parameter) {
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    if (header != null && !header.value().isEmpty()) {
      return header.value();
    }
    RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
    if (param != null && !param.value().isEmpty()) {
      return param.value();
    }
    String name = parameter.getParameterName();
    return name == null ? "request" : name;
  }

  private static String jsonPath(JsonMappingException mapping) {
    return mapping.getPath().stream()
        .map(
            reference ->
                reference.getFieldName() != null
                    ? reference.getFieldName()
                    : String.valueOf(reference.getIndex()))
        .collect(Collectors.joining("."));
  }

  private static Map<String, String> fieldError(FieldError fieldError) {
    return error(fieldError.getField(), fieldError.getDefaultMessage());
  }

  private static Map<String, String> error(String field, String reason) {
    return Map.of("field", field, "reason", reason == null ? INVALID_FORMAT : reason);
  }

  private static String commonCode(HttpStatusCode status) {
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
      case 409 -> "요청 충돌";
      case 503 -> "일시적 서비스 불가";
      default -> status.is4xxClientError() ? "요청 거절" : "서버 오류";
    };
  }

  private static String commonDetail(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "입력값을 확인해 주세요.";
      case 401 -> "인증이 필요합니다.";
      case 403 -> "이 작업을 수행할 권한이 없습니다.";
      case 404 -> "요청한 대상을 찾을 수 없습니다.";
      default -> "요청을 처리할 수 없습니다.";
    };
  }
}
