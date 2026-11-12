package com.commercesettle.commerce.infrastructure.scheduling;

import com.commercesettle.commerce.application.service.CouponService;
import com.commercesettle.observability.RepeatedWarning;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 기한이 지난 미사용 쿠폰을 만료하고 약속액을 상쇄한다. 사용 가능 여부는 요청 때도 서버 시각으로 기한을 검사하므로 이 작업이 늦어도 기한이 늘지 않는다. 쿠폰마다 별도
 * 트랜잭션으로 처리해 한 건의 잠금 대기·실패가 나머지를 막지 않으며, 실패한 건은 다음 실행에서 다시 찾는다.
 */
public class CouponExpiryScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(CouponExpiryScheduler.class);
  private final RepeatedWarning expireFailed = new RepeatedWarning();

  private final CouponService coupons;
  private final Clock clock;
  private final int batchSize;

  public CouponExpiryScheduler(CouponService coupons, Clock clock, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Invalid coupon expiry batch size");
    }
    this.coupons = coupons;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.coupons.expiry-interval-ms:60000}",
      fixedDelayString = "${app.coupons.expiry-interval-ms:60000}")
  public void run() {
    Instant now = clock.instant();
    for (UUID couponId : coupons.expiredCouponIds(now, batchSize)) {
      try {
        coupons.expireIfDue(couponId, now);
      } catch (RuntimeException e) {
        // 예외 메시지는 남기지 않고 종류만 기록한다.
        expireFailed
            .at(LOG)
            .addKeyValue("event", "coupon.expire-failed")
            .addKeyValue("couponId", couponId.toString())
            .addKeyValue("errorType", e.getClass().getName())
            .log("Coupon expiry failed; it will be retried on the next run");
      }
    }
  }
}
