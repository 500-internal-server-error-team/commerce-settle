package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.ReconciliationEventPageView;
import com.commercesettle.payment.application.port.ReconciliationEventReader;
import com.commercesettle.payment.application.port.ReconciliationEventReader.Position;
import com.commercesettle.payment.application.port.ReconciliationEventReader.ReconciliationEvent;
import com.commercesettle.payment.domain.exception.InvalidReconciliationCursorException;
import com.commercesettle.payment.domain.exception.ReconciliationCutoffNotReadyException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement의 정산 대조용 금융 이벤트 피드(docs/api.md#정산-대조-조회). 최초 조회에서 DB 시각으로 기준선 시각(snapshotAt)을 정하고,
 * cursor에 기준선 시각·마감·size·페이지 위치·마지막 행 위치를 묶어 이후 페이지가 같은 이벤트 집합을 읽게 한다.
 *
 * <p>마감 뒤 안전 여유가 지나야 기준선을 고정한다. 안전 여유는 결제 업무 트랜잭션의 최대 시간보다 길게 두므로, 원천 시각이 마감 이하인 이벤트는 기준선 시각 전에 모두
 * 커밋돼 있다.
 */
@Service
public class ReconciliationQueryService {
  private static final String VERSION = "v1";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final ReconciliationEventReader reader;
  private final Duration safetyMargin;

  public ReconciliationQueryService(
      ReconciliationEventReader reader,
      @Value("${app.payment-reconciliation.safety-margin:PT10M}") Duration safetyMargin) {
    if (safetyMargin.isNegative() || safetyMargin.isZero()) {
      throw new IllegalArgumentException("Reconciliation safety margin must be positive");
    }
    this.reader = reader;
    this.safetyMargin = safetyMargin;
  }

  @Transactional(readOnly = true)
  public ReconciliationEventPageView read(Instant cutoffAt, String cursor, int size) {
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid reconciliation page size");
    }
    return read(cutoffAt, null, cursor, size);
  }

  @Transactional(readOnly = true)
  public ReconciliationEventPageView read(
      Instant cutoffAt, Instant snapshotAt, String cursor, int size) {
    if (size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid reconciliation page size");
    Instant cutoff = cutoffAt.truncatedTo(ChronoUnit.MICROS);
    Instant now = reader.now().truncatedTo(ChronoUnit.MICROS);
    if (cutoff.plus(safetyMargin).isAfter(now)) {
      throw new ReconciliationCutoffNotReadyException();
    }
    Cursor at;
    if (cursor == null) {
      Instant snapshot = snapshotAt == null ? now : snapshotAt.truncatedTo(ChronoUnit.MICROS);
      if (snapshot.isAfter(now) || snapshot.isBefore(cutoff.plus(safetyMargin)))
        throw new InvalidReconciliationCursorException();
      at = new Cursor(snapshot, cutoff, size, 0, null);
    } else {
      at = Cursor.decode(cursor);
      if ((snapshotAt != null
          && !at.snapshotAt().equals(snapshotAt.truncatedTo(ChronoUnit.MICROS))))
        throw new InvalidReconciliationCursorException();
      if (!at.cutoffAt().equals(cutoff)
          || at.size() != size
          || at.snapshotAt().isAfter(now)
          || at.snapshotAt().isBefore(cutoff.plus(safetyMargin))) {
        throw new InvalidReconciliationCursorException();
      }
    }

    reader.requireClosedCutoff(cutoff);
    long total = reader.count(at.cutoffAt(), at.snapshotAt());
    List<ReconciliationEvent> rows =
        reader.read(at.cutoffAt(), at.snapshotAt(), at.after(), size + 1);
    boolean more = rows.size() > size;
    List<ReconciliationEvent> items = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? new Cursor(
                    at.snapshotAt(), at.cutoffAt(), size, at.page() + 1, items.getLast().position())
                .encode()
            : null;
    return new ReconciliationEventPageView(
        List.copyOf(items), at.page(), size, total, at.cutoffAt(), at.snapshotAt(), next);
  }

  /** 불투명 cursor. 시각은 epoch 마이크로초로 담아 DB 정밀도와 맞춘다. */
  record Cursor(Instant snapshotAt, Instant cutoffAt, int size, int page, Position after) {
    String encode() {
      String raw =
          String.join(
              "|",
              VERSION,
              Long.toString(micros(snapshotAt)),
              Long.toString(micros(cutoffAt)),
              Integer.toString(size),
              Integer.toString(page),
              Long.toString(micros(after.createdAt())),
              after.eventId().toString());
      return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String value) {
      try {
        String[] parts = new String(DECODER.decode(value), StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 7 || !VERSION.equals(parts[0])) {
          throw new InvalidReconciliationCursorException();
        }
        Instant snapshot = instant(Long.parseLong(parts[1]));
        Instant cutoff = instant(Long.parseLong(parts[2]));
        int size = Integer.parseInt(parts[3]);
        int page = Integer.parseInt(parts[4]);
        Position after = new Position(instant(Long.parseLong(parts[5])), UUID.fromString(parts[6]));
        if (size < 1 || size > 100 || page < 1 || snapshot.isBefore(cutoff)) {
          throw new InvalidReconciliationCursorException();
        }
        return new Cursor(snapshot, cutoff, size, page, after);
      } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
        throw new InvalidReconciliationCursorException();
      }
    }

    private static long micros(Instant value) {
      return ChronoUnit.MICROS.between(Instant.EPOCH, value);
    }

    private static Instant instant(long micros) {
      return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }
  }
}
