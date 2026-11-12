package com.commercesettle.financemock.application.exception;

/** 요청한 대역·버전의 시나리오가 없다. 거래 부재나 확정 거절이 아니다. */
public class ScenarioUnavailableException extends RuntimeException {
  public ScenarioUnavailableException(String message) {
    super(message);
  }
}
