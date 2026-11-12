package com.commercesettle.commerce.domain.model;

/** 로그인 주체의 계정 종류. 구매자와 판매자는 이메일·세션·폐기 범위를 공유하지 않는다. */
public enum ActorKind {
  BUYER,
  SELLER
}
