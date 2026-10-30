package com.commercesettle.financemock.application.dto;

import com.commercesettle.financemock.domain.model.MockTransaction;

/** 응답에 필요한 거래와 재원 계정 참조. 재원 계정은 거래에 ID로만 저장돼 있다. */
public record MockTransactionView(MockTransaction transaction, String fundingAccountRef) {}
