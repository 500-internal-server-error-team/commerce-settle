package com.commercesettle.financemock.application.dto;

/** 지급 실행 결과. {@code responseLost}이면 금융 결과는 이미 저장됐지만 호출자에게 응답을 전달하지 못한 상황을 재현한다. */
public record PayoutResult(MockTransactionView view, boolean responseLost) {}
