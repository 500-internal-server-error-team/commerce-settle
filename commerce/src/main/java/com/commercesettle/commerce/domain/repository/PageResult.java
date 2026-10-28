package com.commercesettle.commerce.domain.repository;

import java.util.List;

/** 목록 조회 결과. page는 0부터 시작한다. */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {
  public PageResult {
    items = List.copyOf(items);
  }

  public int totalPages() {
    return (int) ((totalElements + size - 1) / size);
  }
}
