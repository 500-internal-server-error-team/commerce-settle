package com.commercesettle.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway.RefundRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubRefundGatewayTest {

  private final StubRefundGateway gateway =
      new StubRefundGateway(Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));

  @Test
  @DisplayName("같은 업무 키의 재요청과 조회는 처음 환불 결과를 돌려준다")
  void returnsSameResultForSameBusinessKey() {
    RefundRequest request = request(UUID.randomUUID());

    Result first = gateway.refund(request);

    assertThat(first).isInstanceOf(Result.Succeeded.class);
    assertThat(gateway.refund(request)).isEqualTo(first);
    assertThat(gateway.lookup(request)).isEqualTo(first);
  }

  @Test
  @DisplayName("처리하지 않은 업무 키 조회는 거래 부재로 답해 같은 키로 재전송하게 한다")
  void reportsNotFoundForUnknownKey() {
    assertThat(gateway.lookup(request(UUID.randomUUID()))).isInstanceOf(Result.NotFound.class);
  }

  private static RefundRequest request(UUID businessKey) {
    return new RefundRequest("PG_A", UUID.randomUUID(), businessKey, 8_000, "pg-normal-v1");
  }
}
