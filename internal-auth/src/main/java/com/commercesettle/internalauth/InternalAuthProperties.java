package com.commercesettle.internalauth;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서비스가 받는 내부 호출의 판정 설정. 값이 없거나 잘못되면 기동하지 않는다.
 *
 * @param service 명세 {@code x-owner-service}와 같은 소유 서비스 이름
 * @param requiredSender 반드시 자격을 설정해야 하는 발신자 ID. 사용자 요청을 받는 서비스는 {@code gateway}다.
 * @param senders 발신자 ID(`gateway`, `service:<이름>`, `operator:<식별자>`)별 자격
 * @param excludedPaths 자격 없이 GET으로 조회하는 경로 패턴. 기본값은 Gateway가 모으는 서비스 명세다.
 */
@ConfigurationProperties("app.internal-auth")
public record InternalAuthProperties(
    String service,
    @DefaultValue("gateway") String requiredSender,
    Map<String, Sender> senders,
    @DefaultValue("/v3/api-docs/**") List<String> excludedPaths) {
  /**
   * @param current 현재 자격
   * @param previous 교체 중에만 함께 허용하는 이전 자격. 평소에는 비워 둔다.
   */
  public record Sender(String current, String previous) {}
}
