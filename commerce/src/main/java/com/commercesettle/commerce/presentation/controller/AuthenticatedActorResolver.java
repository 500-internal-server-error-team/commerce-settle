package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.List;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gateway의 신원 헤더에서 계정 종류·ID를 읽는다. 없거나 형식이 틀리면 401이다.
 *
 * <p>백엔드의 Gateway 호출 자격 검사(ADR 0010)가 아직 없어 헤더를 위조한 직접 호출을 막지 못한다. 로컬 개발 환경에서만 사용하며, 자격 검사는 이 경계 앞에
 * 둔다.
 */
@Component
class AuthenticatedActorResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {
  static final String ACTOR_KIND = "X-Authenticated-Actor-Kind";
  static final String ACTOR_ID = "X-Authenticated-Actor-Id";

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(this);
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == AuthenticatedActor.class;
  }

  @Override
  public AuthenticatedActor resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String kind = webRequest.getHeader(ACTOR_KIND);
    String id = webRequest.getHeader(ACTOR_ID);
    if (kind == null || id == null) {
      throw new AuthenticationFailedException();
    }
    try {
      return new AuthenticatedActor(ActorKind.valueOf(kind), UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      throw new AuthenticationFailedException();
    }
  }
}
