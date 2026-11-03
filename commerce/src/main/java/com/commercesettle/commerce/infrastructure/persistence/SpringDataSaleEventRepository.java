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

  /** 엔티티가 아닌 값으로 읽어 영속성 컨텍스트의 사본 대신 DB의 현재 행을 돌려준다. */
  @Query(
      """
      select e.status as status, e.startsAt as startsAt, e.endsAt as endsAt, e.quota as quota,
        e.soldCount as soldCount, e.reservedCount as reservedCount
      from SaleEvent e where e.id = :eventId
      """)
  Optional<AdmissionRow> findAdmissionRowById(UUID eventId);

  interface AdmissionRow {
    SaleEventStatus getStatus();

    Instant getStartsAt();

    Instant getEndsAt();

    long getQuota();

    long getSoldCount();

    long getReservedCount();
  }

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

  /** 참여·종료가 잡고 있는 이벤트 행은 기다리지 않고 건너뛴다. 건너뛴 행은 다음 실행에서 다시 찾는다. */
  @Query(
      value =
          """
          SELECT * FROM commerce.sale_events
          WHERE status = 'SCHEDULED' AND starts_at <= :now AND ends_at > :now
          ORDER BY starts_at, id
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<SaleEvent> findStartedSkippingLocked(Instant now, int limit);

  @Query(
      """
      select e.id from SaleEvent e
      where e.status in :statuses and e.endsAt <= :now
      order by e.endsAt, e.id
      """)
  List<UUID> findIdsEndedBefore(
      Collection<SaleEventStatus> statuses, Instant now, Pageable pageable);
}
