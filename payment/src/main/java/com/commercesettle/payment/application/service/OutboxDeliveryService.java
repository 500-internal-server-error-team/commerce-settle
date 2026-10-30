package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.port.OutboxStore;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import com.commercesettle.payment.application.port.OutboxStore.Publication;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDeliveryService {
  private final OutboxStore store;

  public OutboxDeliveryService(OutboxStore store) {
    this.store = store;
  }

  @Transactional
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts) {
    if (limit < 1 || limit > 100 || leaseMs <= 0 || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("Invalid outbox claim limits");
    }
    return store.claim(limit, leaseMs, maxAttempts);
  }

  @Transactional
  public boolean markPublished(Delivery delivery, Publication publication) {
    if (!delivery.topic().equals(publication.topic())
        || publication.partition() < 0
        || publication.offset() < 0) {
      throw new IllegalArgumentException("Invalid outbox publication position");
    }
    return store.markPublished(delivery, publication);
  }

  @Transactional
  public boolean markFailed(Delivery delivery, int maxAttempts) {
    return store.markFailed(delivery, maxAttempts);
  }
}
