package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.event.EventEnvelope;
import java.util.UUID;

/** Settlement 발행 이벤트를 업무 저장과 같은 트랜잭션에서 원문 bytes로 저장한다. 발행은 Relay가 커밋 뒤에 한다. 트랜잭션 밖에서 호출하면 거절한다. */
public interface SettlementEventOutbox {
  String TOPIC = "commerce-settle.settlement.v1";

  /** 반환값은 저장한 eventId다. 같은 eventId의 같은 본문이면 한 건만 남고, 다른 본문이면 거절한다. */
  UUID append(EventEnvelope<?> event, UUID key);
}
