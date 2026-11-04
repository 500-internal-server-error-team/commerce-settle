package com.commercesettle.democontrol;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class DemoExceptionHandler extends ResponseEntityExceptionHandler {
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException error,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail body =
        problem(status, "DEMO_VALIDATION_FAILED", "입력값을 확인해 주세요.", servlet(request));
    body.setProperty(
        "errors",
        error.getBindingResult().getAllErrors().stream()
            .map(
                e ->
                    Map.of(
                        "field",
                        e instanceof FieldError field ? field.getField() : "body",
                        "reason",
                        e.getDefaultMessage() == null ? "입력 조건을 확인하세요." : e.getDefaultMessage()))
            .toList());
    return new ResponseEntity<>(body, headers, status);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception error,
      Object ignored,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String detail = status.is4xxClientError() ? "요청의 형식과 입력 조건을 확인해 주세요." : "요청을 처리하지 못했습니다.";
    if (error instanceof ResponseStatusException rejected && rejected.getReason() != null)
      detail = rejected.getReason();
    ProblemDetail body = problem(status, "DEMO_REQUEST_REJECTED", detail, servlet(request));
    if (status.value() == 400) {
      String field =
          error instanceof MethodArgumentTypeMismatchException mismatch
              ? mismatch.getName()
              : "body";
      if (error instanceof HttpMessageNotReadableException
          && error.getCause() instanceof JsonMappingException mapping) {
        String path =
            mapping.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        if (!path.isEmpty()) field = path;
      }
      body.setProperty("errors", List.of(Map.of("field", field, "reason", detail)));
    }
    return new ResponseEntity<>(body, headers, status);
  }

  static ProblemDetail problem(
      HttpStatusCode status, String code, String detail, HttpServletRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setType(
        URI.create(
            "urn:commerce-settle:error:"
                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
    body.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
    body.setInstance(URI.create(request.getRequestURI()));
    body.setProperty("code", code);
    String traceId = MDC.get("traceId");
    body.setProperty("traceId", traceId == null ? "" : traceId);
    return body;
  }

  private static HttpServletRequest servlet(WebRequest request) {
    return ((ServletWebRequest) request).getRequest();
  }
}
