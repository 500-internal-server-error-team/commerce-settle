package com.commercesettle.settlement.application.dto;

import java.util.List;

/** 목록 조회 결과. page는 0부터 시작한다. */
public record PageView<T>(List<T> items, int page, int size, long totalElements) {
  public PageView {
    items = List.copyOf(items);
  }

  public int totalPages() {
    return (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
  }
}
