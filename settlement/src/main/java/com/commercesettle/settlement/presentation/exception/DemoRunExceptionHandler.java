package com.commercesettle.settlement.presentation.exception;

import com.commercesettle.settlement.application.exception.DemoRunException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-10)
public class DemoRunExceptionHandler {
  @ExceptionHandler(DemoRunException.class)
  public ProblemDetail handle(DemoRunException e, HttpServletRequest request) {
    int status =
        e.code().equals("DEMO_DISABLED") || e.code().equals("DEMO_SOURCE_UNAVAILABLE")
            ? 503
            : e.code().equals("DEMO_NOT_FOUND") ? 404 : 409;
    var p =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(status), e.getMessage());
    p.setType(URI.create("urn:problem:settlement:" + e.code().toLowerCase(java.util.Locale.ROOT)));
    p.setTitle(e.code());
    p.setInstance(URI.create(request.getRequestURI()));
    p.setProperty("code", e.code());
    p.setProperty("traceId", org.slf4j.MDC.get("traceId"));
    return p;
  }
}
