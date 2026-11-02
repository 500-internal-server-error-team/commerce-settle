package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.model.SaleEventStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SpringDataSaleEventRepository extends JpaRepository<SaleEvent, UUID> {
  @Query("select e.productId from SaleEvent e where e.id = :eventId")
  Optional<UUID> findProductIdById(UUID eventId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from SaleEvent e where e.id = :eventId")
  Optional<SaleEvent> findLockedById(UUID eventId);

  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE commerce.sale_events
          SET reserved_count = reserved_count + 1
          WHERE id = :eventId AND status IN ('SCHEDULED', 'OPEN')
            AND starts_at <= :now AND ends_at > :now
            AND sold_count + reserved_count < quota
          """,
      nativeQuery = true)
  int incrementReservedIfAdmitting(UUID eventId, Instant now);

  boolean existsByProductIdAndStatusIn(UUID productId, Collection<SaleEventStatus> statuses);

  @Query(
      """
      select count(e) > 0 from SaleEvent e
      where e.productId = :productId and e.id <> :exceptEventId and e.status in :statuses
        and e.startsAt < :endsAt and e.endsAt > :startsAt
      """)
  boolean existsOverlap(
      UUID productId,
      UUID exceptEventId,
      Collection<SaleEventStatus> statuses,
      Instant startsAt,
      Instant endsAt);

  Page<SaleEvent> findBySellerId(UUID sellerId, Pageable pageable);

  Page<SaleEvent> findByStatusIn(Collection<SaleEventStatus> statuses, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select e from SaleEvent e
      where e.status = :status and e.startsAt <= :now and e.endsAt > :now
      order by e.startsAt, e.id
      """)
  List<SaleEvent> findLockedByStatusStartedBefore(
      SaleEventStatus status, Instant now, Pageable pageable);

  @Query(
      """
      select e.id from SaleEvent e
      where e.status in :statuses and e.endsAt <= :now
      order by e.endsAt, e.id
      """)
  List<UUID> findIdsEndedBefore(
      Collection<SaleEventStatus> statuses, Instant now, Pageable pageable);
}
