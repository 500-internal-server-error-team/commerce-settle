package com.commercesettle.payment.presentation.exception;

import com.commercesettle.payment.domain.exception.QuarantineNotFoundException;
import com.commercesettle.payment.domain.exception.QuarantineOperationRejectedException;
import com.commercesettle.payment.presentation.controller.PaymentQuarantineController;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 격리 원문 운영 API의 업무 거절을 공통 오류 계약(application/problem+json, code, 실제 traceId)으로 바꾼다. 형식 오류·인증·저장소 장애
 * 등 나머지는 {@link PaymentExceptionHandler}가 맡는다.
 *
 * <p>같은 키 다른 본문(멱등 충돌)은 {@link PaymentExceptionHandler}가 COMMON_IDEMPOTENCY_CONFLICT로 응답한다. 버전 충돌
 * code는 Settlement 격리 운영 API와 같은 값이다. 공통 {@link ErrorCode}에 같은 code를 더하는 다른 변경과 겹치지 않도록 이 API의 거절만
 * 여기서 처리한다.
 */
@RestControllerAdvice(assignableTypes = PaymentQuarantineController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuarantineOperationExceptionHandler {
  private final ObjectProvider<Tracer> tracer;

  public QuarantineOperationExceptionHandler(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @ExceptionHandler(QuarantineNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return respond(
        HttpStatus.NOT_FOUND, "COMMON_RESOURCE_NOT_FOUND", "격리 기록을 찾을 수 없습니다.", null, request);
  }

  @ExceptionHandler(QuarantineOperationRejectedException.class)
  ResponseEntity<ProblemDetail> rejected(
      QuarantineOperationRejectedException e, HttpServletRequest request) {
    String detail =
        switch (e.reason()) {
          case VERSION_CONFLICT -> "다른 변경이 먼저 반영되었습니다. 다시 조회해 주세요.";
          case REPLAY_ACTIVE -> "같은 격리 원문의 재처리가 아직 발행 중입니다. 발행이 끝난 뒤 다시 요청해 주세요.";
          case DELIVERY_NOT_RESUMABLE -> "시도를 다 쓴 발행 작업만 재개할 수 있습니다.";
        };
    return respond(HttpStatus.CONFLICT, e.reason().code(), detail, null, request);
  }

  /** 요청 본문 검증 오류를 필드별 사유와 함께 돌려준다. 거절된 입력 값은 싣지 않는다. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                field ->
                    Map.of(
                        "field",
                        field.getField(),
                        "reason",
                        field.getDefaultMessage() == null
                            ? "형식이 올바르지 않습니다."
                            : field.getDefaultMessage()))
            .toList();
    return respond(
        HttpStatus.BAD_REQUEST, "COMMON_VALIDATION_FAILED", "입력값을 확인해 주세요.", errors, request);
  }

  private ResponseEntity<ProblemDetail> respond(
      HttpStatus status,
      String code,
      String detail,
      List<Map<String, String>> errors,
      HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create("urn:commerce-settle:error:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setTitle(
        switch (status.value()) {
          case 400 -> "잘못된 요청";
          case 404 -> "대상 없음";
          default -> "요청 충돌";
        });
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("traceId", traceId());
    if (errors != null && !errors.isEmpty()) {
      problem.setProperty("errors", errors);
    }
    return ResponseEntity.status(status).body(problem);
  }

  private String traceId() {
    Tracer current = tracer.getIfAvailable(() -> Tracer.NOOP);
    Span span = current.currentSpan();
    return span == null ? "" : span.context().traceId();
  }
}
