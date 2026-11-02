package com.commercesettle.payment.application.port;

import java.util.UUID;

/** 선점 한 번의 결과. 한 번에 한 행만 다루며, 처리할 요청을 잡았거나 그 행을 처리하지 않고 확인 대상으로 넘겼다. 처리기는 둘 다 처리 1건으로 센다. */
public sealed interface InboxClaim {
  /** 처리할 요청을 선점했다. */
  record Claimed(WaitingInboxEvent event) implements InboxClaim {}

  /** 시도 상한을 넘겼거나 원문을 읽을 수 없어 처리하지 않고 REVIEW_REQUIRED로 넘겼다. */
  record Reviewed(UUID inboxId, String errorCode) implements InboxClaim {}
}
