package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.model.SaleEventStatus;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.SaleEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaSaleEventRepository implements SaleEventRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
  private static final List<SaleEventStatus> IN_PROGRESS =
      List.of(SaleEventStatus.SCHEDULED, SaleEventStatus.OPEN);
  private static final List<SaleEventStatus> PUBLISHED_PERIOD =
      List.of(SaleEventStatus.SCHEDULED, SaleEventStatus.OPEN, SaleEventStatus.SOLD_OUT);

  private final SpringDataSaleEventRepository events;
  private final JdbcOperations jdbc;

  JpaSaleEventRepository(SpringDataSaleEventRepository events, JdbcOperations jdbc) {
    this.events = events;
    this.jdbc = jdbc;
  }

  /** 다른 트랜잭션이 잡은 이벤트 행의 갱신 대기도 잠금 대기 상한 안에서 끝낸다. */
  @Override
  public SaleEvent save(SaleEvent event) {
    TransactionLimits.apply(jdbc);
    return events.saveAndFlush(event);
  }

  @Override
  public Optional<SaleEvent> findById(UUID eventId) {
    return events.findById(eventId);
  }

  @Override
  public Optional<UUID> findProductIdById(UUID eventId) {
    return events.findProductIdById(eventId);
  }

  @Override
  public Optional<SaleEvent> findByIdForUpdate(UUID eventId) {
    TransactionLimits.apply(jdbc);
    return events.findLockedById(eventId);
  }

  /** 확정·종료 트랜잭션이 이벤트 행을 잠그고 있으면 잠금 대기 상한 안에서 끝낸다. */
  @Override
  public boolean reserveSlot(UUID eventId, Instant now) {
    TransactionLimits.apply(jdbc);
    return events.incrementReservedIfAdmitting(eventId, now.truncatedTo(ChronoUnit.MICROS)) == 1;
  }

  @Override
  public boolean existsInProgressByProductId(UUID productId) {
    return events.existsByProductIdAndStatusIn(productId, IN_PROGRESS);
  }

  @Override
  public boolean existsPublishedOverlap(
      UUID productId, UUID exceptEventId, Instant startsAt, Instant endsAt) {
    return events.existsOverlap(productId, exceptEventId, PUBLISHED_PERIOD, startsAt, endsAt);
  }

  @Override
  public PageResult<SaleEvent> findBySeller(UUID sellerId, int page, int size) {
    return toResult(events.findBySellerId(sellerId, PageRequest.of(page, size, NEWEST_FIRST)));
  }

  @Override
  public PageResult<SaleEvent> findPublished(int page, int size) {
    return toResult(
        events.findByStatusNot(SaleEventStatus.DRAFT, PageRequest.of(page, size, NEWEST_FIRST)));
  }

  @Override
  public List<SaleEvent> findStartedForUpdate(Instant now, int limit) {
    TransactionLimits.apply(jdbc);
    return events.findLockedByStatusStartedBefore(
        SaleEventStatus.SCHEDULED, now, PageRequest.ofSize(limit));
  }

  @Override
  public List<UUID> findEndedIds(Instant now, int limit) {
    return events.findIdsEndedBefore(IN_PROGRESS, now, PageRequest.ofSize(limit));
  }

  private static PageResult<SaleEvent> toResult(Page<SaleEvent> page) {
    return new PageResult<>(
        page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
  }
}
