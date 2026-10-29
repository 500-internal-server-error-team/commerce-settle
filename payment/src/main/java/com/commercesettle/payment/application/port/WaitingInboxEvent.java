package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import java.util.UUID;

/** 처리기가 잠근 WAITING Inbox 행과 원문에서 다시 읽은 이벤트. */
public record WaitingInboxEvent(UUID inboxId, EventEnvelope<OrderPaymentRequestedPayload> event) {}
