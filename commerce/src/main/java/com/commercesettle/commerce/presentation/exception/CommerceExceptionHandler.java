package com.commercesettle.commerce.presentation.exception;

import com.commercesettle.commerce.application.exception.AuthChangeUnconfirmedException;
import com.commercesettle.commerce.application.exception.AuthRateLimitedException;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.exception.OrderStubException;
import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
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

  @ExceptionHandler(OrderStubException.class)
  ResponseEntity<Object> orderStubFailure(OrderStubException e, WebRequest request) {
    return switch (e.kind()) {
      case NOT_FOUND -> respond(ErrorCode.COMMON_RESOURCE_NOT_FOUND, request);
      case INVALID_REQUEST -> invalid(List.of(error(e.field(), "입력값의 조합을 확인해 주세요.")), request);
    };
  }

  @ExceptionHandler(ProductNotFoundException.class)
  ResponseEntity<Object> productNotFound(ProductNotFoundException e, WebRequest request) {
    return respond(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "상품을 찾을 수 없습니다.", null, request);
  }

  @ExceptionHandler({VersionConflictException.class, OptimisticLockingFailureException.class})
  ResponseEntity<Object> versionConflict(RuntimeException e, WebRequest request) {
    return respond(ErrorCode.COMMON_VERSION_CONFLICT, request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<Object> idempotencyConflict(IdempotencyConflictException e, WebRequest request) {
    return respond(ErrorCode.COMMON_IDEMPOTENCY_CONFLICT, request);
  }

  @ExceptionHandler(InsufficientInventoryException.class)
  ResponseEntity<Object> insufficientInventory(
      InsufficientInventoryException e, WebRequest request) {
    return respond(ErrorCode.INVENTORY_INSUFFICIENT, request);
  }

  @ExceptionHandler(DuplicateAccountEmailException.class)
  ResponseEntity<Object> duplicateEmail(DuplicateAccountEmailException e, WebRequest request) {
    return respond(ErrorCode.ACCOUNT_EMAIL_DUPLICATED, request);
  }

  @ExceptionHandler(AuthenticationFailedException.class)
  ResponseEntity<Object> authenticationFailed(AuthenticationFailedException e, WebRequest request) {
    return respond(ErrorCode.COMMON_AUTHENTICATION_REQUIRED, request);
  }

  @ExceptionHandler(AuthRateLimitedException.class)
  ResponseEntity<Object> rateLimited(AuthRateLimitedException e, WebRequest request) {
    ErrorCode code = ErrorCode.COMMON_RATE_LIMITED;
    return ResponseEntity.status(code.status())
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .body(problem(code.status(), code, code.detail(), request));
  }

  /** 인증 저장소를 확인할 수 없으면 인증 실패로 바꾸지 않는다. 세션은 발급되지 않았거나 삭제를 확인하지 못한 상태다. */
  @ExceptionHandler(AuthenticationUnavailableException.class)
  ResponseEntity<Object> authenticationUnavailable(
      AuthenticationUnavailableException e, WebRequest request) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "commerce.auth")
        .addKeyValue("errorCode", ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE.name())
        .addKeyValue("errorType", errorType(e))
        .addKeyValue("traceId", traceId())
        .log("Auth store is temporarily unavailable");
    return respond(
        ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE,
        "인증 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
        null,
        request);
  }

  /** 가드를 막은 뒤 결과를 확정하지 못했다. 비밀번호가 이미 바뀌었을 수 있으므로 재전송을 안내하지 않는다. 변경 ID는 재조정에 쓰는 추적 값이며 비밀값이 아니다. */
  @ExceptionHandler(AuthChangeUnconfirmedException.class)
  ResponseEntity<Object> authChangeUnconfirmed(
      AuthChangeUnconfirmedException e, WebRequest request) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "commerce.auth.change")
        .addKeyValue("result", "UNKNOWN")
        .addKeyValue("changeId", e.changeId().toString())
        .addKeyValue("errorCode", ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE.name())
        .addKeyValue("errorType", errorType(e))
        .addKeyValue("traceId", traceId())
        .log("Account auth change is not confirmed");
    return respond(
        ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE,
        "변경 결과를 확인할 수 없습니다. 같은 요청을 다시 보내지 말고 새 비밀번호로 로그인해 확인해 주세요.",
        null,
        request);
  }

  @ExceptionHandler({TransientDataAccessException.class, CannotCreateTransactionException.class})
  ResponseEntity<Object> temporarilyUnavailable(RuntimeException e, WebRequest request) {
    String traceId = traceId();
    LOGGER
        .atWarn()
        .addKeyValue("event", "commerce.request")
        .addKeyValue("result", "UNKNOWN")
        .addKeyValue("errorCode", ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE.name())
        .addKeyValue("errorType", e.getClass().getName())
        .addKeyValue("traceId", traceId)
        .log("Storage is temporarily unavailable");
    return respond(
        ErrorCode.COMMON_TEMPORARILY_UNAVAILABLE,
        "일시적으로 처리할 수 없습니다. 멱등키를 보낸 요청은 같은 키로 결과를 다시 확인해 주세요.",
        null,
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    LOGGER
        .atError()
        .addKeyValue("event", "commerce.request")
        .addKeyValue("errorCode", ErrorCode.COMMON_INTERNAL_ERROR.name())
        .addKeyValue("errorType", e.getClass().getName())
        .addKeyValue("traceId", traceId())
        .log("Unexpected request failure");
    return respond(ErrorCode.COMMON_INTERNAL_ERROR, request);
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
    ErrorCode code = ErrorCode.forStatus(status);
    ProblemDetail problem = problem(status, code, code.detail(), request);
    return ResponseEntity.status(status).headers(headers).body(problem);
  }

  private ResponseEntity<Object> invalid(List<Map<String, String>> errors, WebRequest request) {
    ErrorCode code = ErrorCode.COMMON_VALIDATION_FAILED;
    return respond(code, code.detail(), errors.isEmpty() ? null : errors, request);
  }

  private ResponseEntity<Object> respond(ErrorCode code, WebRequest request) {
    return respond(code, code.detail(), null, request);
  }

  /** 같은 code라도 상황에 맞는 안내가 따로 있으면 기본 설명 대신 detail로 넘긴다. */
  private ResponseEntity<Object> respond(
      ErrorCode code, String detail, List<Map<String, String>> errors, WebRequest request) {
    ProblemDetail problem = problem(code.status(), code, detail, request);
    if (errors != null) {
      problem.setProperty(ERRORS, errors);
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

  // 저장소 예외의 종류만 남기고 메시지는 기록하지 않는다.
  private static String errorType(Exception e) {
    return (e.getCause() == null ? e : e.getCause()).getClass().getName();
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
