package com.commercesettle.settlement.domain.exception;

/** 지급 전 검증(상품·판매자 일치, 판매자 상태, 수취 계정 사본)을 통과하지 못했다. 그 판매자의 선점·합계는 모두 롤백한다. */
public class PayoutNotAllowedException extends RuntimeException {
  public PayoutNotAllowedException(String reason) {
    super(reason);
  }
}
