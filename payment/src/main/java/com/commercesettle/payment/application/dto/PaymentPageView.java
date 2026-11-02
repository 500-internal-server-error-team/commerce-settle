package com.commercesettle.payment.application.dto;

import java.util.List;

public record PaymentPageView(List<PaymentView> items, int page, int size, long totalElements) {
  public int totalPages() {
    return (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
  }
}
