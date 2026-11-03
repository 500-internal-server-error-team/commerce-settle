package com.commercesettle.democontrol;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class DemoExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> handle(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode())
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            Map.of(
                "status",
                error.getStatusCode().value(),
                "code",
                "DEMO_REQUEST_REJECTED",
                "detail",
                error.getReason() == null ? "요청을 처리하지 못했습니다." : error.getReason()));
  }
}
