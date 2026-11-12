package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 주소가 없거나, 삭제됐거나, 요청한 구매자의 주소가 아니다. */
public class BuyerAddressNotFoundException extends RuntimeException {
  public BuyerAddressNotFoundException(UUID addressId) {
    super("buyer address not found: " + addressId);
  }
}
