package com.commercesettle.settlement.application.dto;

import com.commercesettle.settlement.domain.model.Payout;
import java.time.Instant;

/**
 * 판매자에게 보여 줄 지급과 다음 자동 시도 시각. 다음 시도는 지급 작업이 대기(PENDING·RETRY) 중일 때만 있고, 완료·확정 거절·운영 확인 대기·실행 중이면
 * 없다.
 */
public record PayoutView(Payout payout, Instant nextAttemptAt) {}
