package com.commercesettle.financemock.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 모의 지급 실행 요청. 같은 업무 키의 중복·충돌 판정은 이 본문(업무 키 제외)의 해시로 한다.
 *
 * @param accountRef 출금할 PAYOUT 재원 계정
 * @param sellerAccountRef 판매자 수취 시험 계정
 */
public record MockPayoutRequest(
    @NotNull UUID businessKey,
    @NotBlank @Size(max = 100) String accountRef,
    @NotBlank @Size(max = 100) String sellerAccountRef,
    @NotNull @Positive Long amount,
    @NotBlank @Size(max = 32) String scenarioVersion) {}
