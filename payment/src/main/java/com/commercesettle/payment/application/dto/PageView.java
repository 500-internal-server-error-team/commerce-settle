package com.commercesettle.payment.application.dto;

import java.util.List;
import java.util.function.Function;

/** 공통 목록 페이지. 시작 위치가 int를 넘는 페이지는 행이 있을 수 없어 빈 목록이다. */
public record PageView<T>(List<T> items, int page, int size, long totalElements) {
  public int totalPages() {
    return (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
  }

  public <R> PageView<R> map(Function<T, R> mapper) {
    return new PageView<>(items.stream().map(mapper).toList(), page, size, totalElements);
  }

  /** page·size를 검사하고, 읽을 수 있는 범위면 {@code reader}로 행을 읽는다. */
  public static <T> PageView<T> read(int page, int size, long total, PageReader<T> reader) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid page");
    }
    List<T> items = (long) page * size > Integer.MAX_VALUE ? List.of() : reader.read(page, size);
    return new PageView<>(items, page, size, total);
  }

  @FunctionalInterface
  public interface PageReader<T> {
    List<T> read(int page, int size);
  }
}
