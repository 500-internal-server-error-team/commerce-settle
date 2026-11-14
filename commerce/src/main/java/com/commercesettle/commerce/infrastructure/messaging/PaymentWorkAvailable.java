package com.commercesettle.commerce.infrastructure.messaging;

/** Inbox 커밋 뒤 처리기를 깨우는 로컬 힌트. */
public record PaymentWorkAvailable(boolean accepted) {}
