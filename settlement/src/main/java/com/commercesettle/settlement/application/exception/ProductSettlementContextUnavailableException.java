package com.commercesettle.settlement.application.exception;

/** 커머스의 지급 컨텍스트를 확인하지 못했다. 판매자를 지급 가능으로 보지 않으며, 그 판매자의 지급 생성을 롤백해 다음 실행에서 다시 고려한다. */
public class ProductSettlementContextUnavailableException extends RuntimeException {
  public ProductSettlementContextUnavailableException(String message) {
    super(message);
  }

  public ProductSettlementContextUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
