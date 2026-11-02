package com.commercesettle.commerce.infrastructure.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 컨트롤러 전에 내부 호출 자격(ADR 0010)으로 발신자를 확인한다.
 *
 * <p>판정 순서: 자격 없음·불일치 401 → 명세에 없는 메서드·경로 404 → 다른 서비스 소유 작업이나 발신자와 맞지 않는 {@code x-actor} 403 →
 * Gateway가 아닌 발신자의 신원 헤더 403. 통과한 요청의 {@code X-Authenticated-*}는 Gateway가 보낸 값이다. 계정 종류·ID·세션 해시의
 * 유무와 작업 역할 대조는 이후 MVC 경계가 맡는다.
 *
 * <p>오류는 MVC 예외 처리기로 넘겨 공통 오류 형식으로 응답한다. 자격 원문·해시는 로그·응답·지표에 넣지 않는다.
 */
public class InternalCallerFilter extends OncePerRequestFilter {
  static final String GATEWAY = "gateway";
  private static final Logger LOGGER = LoggerFactory.getLogger(InternalCallerFilter.class);
  private static final String BEARER = "bearer ";
  private static final String IDENTITY_PREFIX = "x-authenticated-";
  private static final String UNKNOWN = "unknown";
  private static final Set<String> GATEWAY_ACTORS = Set.of("PUBLIC", "BUYER", "SELLER");
  // Gateway의 서비스 명세 경로. 공개 계약 문서이므로 자격 판정에서 제외한다.
  private static final PathPattern DOCUMENTS = new PathPatternParser().parse("/v3/api-docs/**");

  private final String service;
  private final InternalCredentials credentials;
  private final InternalOperations operations;
  private final HandlerExceptionResolver errors;
  private final MeterRegistry meters;

  public InternalCallerFilter(
      String service,
      InternalCredentials credentials,
      InternalOperations operations,
      HandlerExceptionResolver errors,
      MeterRegistry meters) {
    this.service = service;
    this.credentials = credentials;
    this.operations = operations;
    this.errors = errors;
    this.meters = meters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    final var path =
        RequestPath.parse(request.getRequestURI(), request.getContextPath())
            .pathWithinApplication();
    if ("GET".equals(request.getMethod()) && DOCUMENTS.matches(path)) {
      chain.doFilter(request, response);
      return;
    }
    final var sender = sender(request);
    if (sender == null) {
      reject(request, response, UNKNOWN, HttpStatus.UNAUTHORIZED);
      return;
    }
    final var operation = operations.find(request.getMethod(), path).orElse(null);
    if (operation == null) {
      reject(request, response, sender, HttpStatus.NOT_FOUND);
      return;
    }
    if (!service.equals(operation.owner())
        || !allows(sender, operation.actors())
        || (!GATEWAY.equals(sender) && hasIdentityHeader(request))) {
      reject(request, response, sender, HttpStatus.FORBIDDEN);
      return;
    }
    request.setAttribute("verifiedInternalCaller", sender);
    chain.doFilter(request, response);
  }

  private String sender(HttpServletRequest request) {
    final var values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
    if (values.size() != 1) {
      return null;
    }
    final var value = values.get(0);
    if (!value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    final var credential = value.substring(BEARER.length());
    return credential.isEmpty() ? null : credentials.sender(credential).orElse(null);
  }

  private static boolean allows(String sender, Set<String> actors) {
    if (GATEWAY.equals(sender)) {
      return actors.stream().anyMatch(GATEWAY_ACTORS::contains);
    }
    if (sender.startsWith("service:")) {
      return actors.contains("SERVICE:" + sender.substring("service:".length()));
    }
    return actors.contains("OPERATOR");
  }

  private static boolean hasIdentityHeader(HttpServletRequest request) {
    return Collections.list(request.getHeaderNames()).stream()
        .anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith(IDENTITY_PREFIX));
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, String sender, HttpStatus status)
      throws ServletException {
    meters
        .counter(
            "internal.auth.rejections",
            "sender",
            sender,
            "status",
            Integer.toString(status.value()))
        .increment();
    LOGGER
        .atWarn()
        .addKeyValue("event", "commerce.internal-auth")
        .addKeyValue("sender", sender)
        .addKeyValue("method", request.getMethod())
        .addKeyValue("httpStatus", status.value())
        .log("Internal caller rejected");
    final var handled =
        errors.resolveException(request, response, null, new ErrorResponseException(status));
    if (handled == null) {
      throw new ServletException("Internal caller rejection was not handled");
    }
  }
}
