package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.settlement.application.port.InboxOperationStore.Claim;
import com.commercesettle.settlement.application.service.InboxOperationService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/** 한 번에 한 대상을 임대한다. 재기동 뒤에는 같은 대상의 만료 임대를 회수한다. */
@Component
@ConditionalOnProperty(
    name = "settlement.inbox-rejudgement.enabled",
    havingValue = "true",
    matchIfMissing = true)
class InboxRejudgementScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(InboxRejudgementScheduler.class);
  private final RepeatedWarning dependencyUnavailable = new RepeatedWarning();
  private final InboxOperationService service;
  private final Duration lease;

  InboxRejudgementScheduler(
      InboxOperationService service,
      @Value("${settlement.inbox-rejudgement.lease:30s}") Duration lease) {
    this.service = service;
    this.lease = lease;
  }

  @Scheduled(fixedDelayString = "${settlement.inbox-rejudgement.interval-ms:1000}")
  void poll() {
    for (int i = 0; i < 32; i++) {
      final Claim claim;
      try {
        var next = service.claim(lease);
        if (next.isEmpty()) return;
        claim = next.get();
      } catch (RuntimeException e) {
        log(e, "CLAIM", null);
        return;
      }
      try {
        service.process(claim);
      } catch (RuntimeException e) {
        log(e, "APPLY", claim);
        try {
          service.fail(claim);
        } catch (RuntimeException failed) {
          log(failed, "FAILURE_RECORD", claim);
          return;
        }
      }
    }
  }

  private void log(RuntimeException error, String stage, Claim claim) {
    boolean dependencyUnavailable =
        error instanceof TransientDataAccessException
            || error instanceof DataAccessResourceFailureException
            || error instanceof CannotCreateTransactionException;
    var event = dependencyUnavailable ? this.dependencyUnavailable.at(LOG) : LOG.atError();
    event
        .addKeyValue("event", "settlement.inbox-rejudgement")
        .addKeyValue("errorCode", "PROCESSING_FAILED")
        .addKeyValue("errorType", error.getClass().getName())
        .addKeyValue("stage", stage);
    if (claim != null)
      event
          .addKeyValue("runId", claim.runId())
          .addKeyValue("targetId", claim.targetId())
          .addKeyValue("inboxId", claim.inboxId());
    event.log("Inbox rejudgement failed");
  }
}
