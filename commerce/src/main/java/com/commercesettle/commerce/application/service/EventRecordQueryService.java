package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.port.EventRecordReader;
import com.commercesettle.commerce.application.port.EventRecordReader.Entry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventRecordQueryService {
  private final EventRecordReader reader;

  public EventRecordQueryService(EventRecordReader reader) {
    this.reader = reader;
  }

  /** 한 번의 제한된 읽기 관측이다. 거래 성공·전달 완료를 판정하지 않는다. */
  @Transactional(readOnly = true, timeout = 3)
  public Snapshot get(List<UUID> ids) {
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 20
        || ids.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("조회할 UUID는 1~20개여야 합니다.");
    }
    var entries = reader.find(ids.stream().distinct().toList());
    return new Snapshot(
        List.copyOf(entries.subList(0, Math.min(50, entries.size()))),
        50,
        entries.size() > 50,
        Instant.now());
  }

  public record Snapshot(List<Entry> records, int limit, boolean truncated, Instant observedAt) {}
}
