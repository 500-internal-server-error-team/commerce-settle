package com.commercesettle.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.regex.Pattern;

/** 수신 계약 해석에 쓰는 엄격한 JSON 규칙. 모르는 필드·빠진 필드·형 변환을 거절한다. */
final class StrictEventJson {
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private StrictEventJson() {}

  static ObjectMapper mapper(ObjectMapper objectMapper) {
    ObjectMapper mapper = objectMapper.copy();
    mapper.enable(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    return mapper;
  }

  /** Jackson은 Base64 문자열도 UUID로 읽으므로 하이픈 표기만 받는다. */
  static void requireUuid(JsonNode node) {
    if (!node.isTextual() || !UUID_PATTERN.matcher(node.textValue()).matches()) {
      throw new IllegalArgumentException();
    }
  }
}
