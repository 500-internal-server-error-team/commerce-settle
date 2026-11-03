package com.commercesettle.commerce.domain.exception;

/** 가입 재요청이 가리키는 계정이 그사이 삭제됐다. */
public class AccountNotFoundException extends RuntimeException {
  public AccountNotFoundException() {
    super("account was not found");
  }
}
