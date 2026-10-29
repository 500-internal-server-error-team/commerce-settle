package com.commercesettle.commerce.domain.exception;

/** 같은 계정 종류에 이미 등록된 이메일. 삭제한 계정의 이메일도 다시 쓰지 않는다. */
public class DuplicateAccountEmailException extends RuntimeException {
  public DuplicateAccountEmailException() {
    super("Account email is already registered");
  }
}
