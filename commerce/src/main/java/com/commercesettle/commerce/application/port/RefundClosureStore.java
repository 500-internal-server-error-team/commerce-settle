package com.commercesettle.commerce.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 구매자 확인과 운영 종결 증빙을 Commerce의 추가 전용 기록으로 보존한다. */
public interface RefundClosureStore {
  void confirm(Confirmation confirmation);

  Optional<Confirmation> find(UUID reference);

  void preserve(UUID reference, UUID requestId, String operator, String snapshot, Instant now);

  record Confirmation(UUID id, RefundClosureEvidence.Subject subject, Instant confirmedAt) {}
}
