package com.commercesettle.commerce.infrastructure.messaging;

/** Outbox 저장 사실을 알리는 내부 신호. 실제 전송 데이터는 DB에 보존한다. */
public record OutboxAppended() {}
