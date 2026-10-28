package com.commercesettle.commerce.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP 요청 본문만 엄격하게 읽는다. 쓰기 DTO는 additionalProperties=false이고 금액·수량은 정수만 받는다. 이벤트 소비처럼 새 필드를 무시해야 하는
 * 다른 경로의 공용 ObjectMapper 설정은 바꾸지 않는다.
 */
@Configuration
public class HttpJsonConfiguration implements WebMvcConfigurer {
  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (HttpMessageConverter<?> converter : converters) {
      if (converter instanceof MappingJackson2HttpMessageConverter json) {
        json.setObjectMapper(strict(json.getObjectMapper()));
      }
    }
  }

  private static ObjectMapper strict(ObjectMapper shared) {
    ObjectMapper mapper = shared.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    return mapper;
  }
}
