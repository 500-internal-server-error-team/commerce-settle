package com.commercesettle.commerce.domain.exception;

/** 같은 구매자의 동시 요청이 기본 주소를 함께 바꿔 한쪽만 반영됐다. 목록을 다시 조회한 뒤 재시도한다. */
public class DefaultAddressConflictException extends RuntimeException {
  public DefaultAddressConflictException() {
    super("default buyer address was changed concurrently");
  }
}
