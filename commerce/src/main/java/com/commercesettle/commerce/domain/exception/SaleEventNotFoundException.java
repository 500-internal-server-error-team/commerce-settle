package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 이벤트가 없거나, 요청 주체가 볼 수 없는 이벤트다. 미공개 이벤트는 판매자 본인만 볼 수 있다. */
public class SaleEventNotFoundException extends RuntimeException {
  public SaleEventNotFoundException(UUID eventId) {
    super("sale event not found: " + eventId);
  }
}
