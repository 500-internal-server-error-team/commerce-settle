package com.commercesettle.commerce.application.exception;

/** 자격 오류·계정 없음·정지/삭제·인증 버전 불일치를 하나로 묶은 인증 실패. 응답으로 계정 존재 여부를 구분하지 않는다. */
public class AuthenticationFailedException extends RuntimeException {
  public AuthenticationFailedException() {
    super("Authentication failed");
  }
}
