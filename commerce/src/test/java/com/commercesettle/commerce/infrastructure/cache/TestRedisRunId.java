package com.commercesettle.commerce.infrastructure.cache;

import java.io.IOException;
import org.testcontainers.containers.GenericContainer;

/** 시험용 Redis 컨테이너의 현재 run_id. 인증 게이트를 열어야 하는 통합 시험이 승인값으로 쓴다. */
public final class TestRedisRunId {
  private TestRedisRunId() {}

  public static String of(GenericContainer<?> redis) {
    // PER_CLASS 시험은 컨테이너 확장보다 컨텍스트를 먼저 만들 수 있다. 이미 실행 중이면 아무것도 하지 않는다.
    redis.start();
    try {
      return RunIdGate.runId(redis.execInContainer("redis-cli", "INFO", "server").getStdout());
    } catch (IOException error) {
      throw new IllegalStateException(error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
