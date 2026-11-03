package com.commercesettle.settlement.application.dto;

import com.commercesettle.settlement.domain.model.Payout;
import java.time.Instant;

/**
 * 판매자에게 보여 줄 지급과 다음 자동 시도 시각. 다음 시도는 지급 작업이 대기(PENDING·RETRY) 중이거나, 실행 중이지만 임대가 만료돼 시도가 남은 채 회수를
 * 기다릴 때만 있다. 완료·확정 거절·운영 확인 대기와 임대가 유효한 실행 중이면 없다.
 */
public record PayoutView(Payout payout, Instant nextAttemptAt) {}
