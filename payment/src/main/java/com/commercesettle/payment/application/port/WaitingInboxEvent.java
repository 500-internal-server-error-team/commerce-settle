package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import java.util.UUID;

/**
 * 처리기가 잠근 WAITING Inbox 행과 원문에서 다시 읽은 이벤트. version과 attempts는 선점 시점의 값으로, 실패 기록이 그 사이에 바뀐 행을 덮어쓰지
 * 않게 한다.
 */
public record WaitingInboxEvent(
    UUID inboxId, long version, int attempts, EventEnvelope<OrderPaymentRequestedPayload> event) {}
