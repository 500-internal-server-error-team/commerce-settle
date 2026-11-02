package com.commercesettle.commerce.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 커머스를 호출할 수 있는 발신자별 내부 호출 자격(ADR 0010). 값이 없거나 잘못되면 기동하지 않는다.
 *
 * @param senders 발신자 ID(`gateway`, `service:<이름>`, `operator:<식별자>`)별 자격
 */
@Validated
@ConfigurationProperties("app.internal-auth")
public record InternalAuthProperties(@NotEmpty Map<String, @Valid @NotNull Sender> senders) {
  /**
   * @param current 현재 자격
   * @param previous 교체 중에만 함께 허용하는 이전 자격. 평소에는 비워 둔다.
   */
  public record Sender(@NotNull String current, String previous) {}
}
