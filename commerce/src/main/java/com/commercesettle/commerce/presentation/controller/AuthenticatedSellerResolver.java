package com.commercesettle.commerce.presentation.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gateway의 신원 헤더에서 판매자를 식별한다. 신원이 없으면 401, 판매자가 아닌 계정이면 403이다.
 *
 * <p>Gateway는 세션을 검증하고 클라이언트의 신원 헤더를 제거한 뒤 다시 붙이지만, 백엔드의 Gateway 호출 자격 검사는 아직 없다. 그 전에는 Gateway를
 * 우회한 직접 호출이 헤더를 위조할 수 있으므로 로컬 개발 환경에서만 사용한다. 자격 검사를 구현할 때 이 경계에서 Gateway 자격을 먼저 확인해야 한다.
 */
@Component
class AuthenticatedSellerResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {
  static final String ACTOR_KIND = "X-Authenticated-Actor-Kind";
  static final String ACTOR_ID = "X-Authenticated-Actor-Id";
  private static final List<String> ACTOR_KINDS = List.of("BUYER", "SELLER");

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(this);
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == AuthenticatedSeller.class;
  }

  @Override
  public AuthenticatedSeller resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String kind = webRequest.getHeader(ACTOR_KIND);
    UUID id = parseId(webRequest.getHeader(ACTOR_ID));
    if (id == null || !ACTOR_KINDS.contains(kind)) {
      throw new ErrorResponseException(HttpStatus.UNAUTHORIZED);
    }
    if (!"SELLER".equals(kind)) {
      throw new ErrorResponseException(HttpStatus.FORBIDDEN);
    }
    return new AuthenticatedSeller(id);
  }

  private static UUID parseId(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
