package com.commercesettle.commerce.application.port;

import java.util.UUID;

/**
 * 복구 토큰을 등록된 수신 경로로 보낼 전달 작업에 넘긴다. 토큰 원문은 응답·로그·금융 이벤트에 싣지 않고 이 암호문으로만 보관한다. 실제 발송은 알림 전달 구현 때
 * 연결한다.
 */
public interface PasswordResetDelivery {
  /** tokenId에 묶인 암호문. 다른 토큰 행으로 옮기면 복호화하지 못한다. */
  byte[] seal(UUID tokenId, String token);
}
