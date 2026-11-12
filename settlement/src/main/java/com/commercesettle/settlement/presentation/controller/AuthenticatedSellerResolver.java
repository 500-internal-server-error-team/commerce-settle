package com.commercesettle.settlement.presentation.controller;

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
 * Gateway의 신원 헤더에서 판매자를 읽는다. 헤더가 없거나 형식이 틀리면 401, 판매자가 아니면 403이다.
 *
 * <p>내부 호출 자격 판정이 컨트롤러 전에 Gateway가 아닌 발신자의 신원 헤더를 거절하므로, 여기 도달한 헤더는 Gateway가 검증해 보낸 값이다.
 */
@Component
class AuthenticatedSellerResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {
  static final String ACTOR_KIND = "X-Authenticated-Actor-Kind";
  static final String ACTOR_ID = "X-Authenticated-Actor-Id";

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(this);
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == AuthenticatedSeller.class;
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String kind = webRequest.getHeader(ACTOR_KIND);
    String id = webRequest.getHeader(ACTOR_ID);
    if (kind == null || id == null || !("BUYER".equals(kind) || "SELLER".equals(kind))) {
      throw new ErrorResponseException(HttpStatus.UNAUTHORIZED);
    }
    UUID actorId;
    try {
      actorId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ErrorResponseException(HttpStatus.UNAUTHORIZED);
    }
    if (!"SELLER".equals(kind)) {
      throw new ErrorResponseException(HttpStatus.FORBIDDEN);
    }
    return new AuthenticatedSeller(actorId);
  }
}
