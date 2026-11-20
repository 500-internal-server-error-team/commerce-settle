package com.commercesettle.commerce.application.port;

import java.util.UUID;

/** 시험용 힌트. true는 DB의 현재 가용 수량을 확인하라는 뜻이다. */
public interface OrderStockHint {
  boolean suggestsShortage(UUID productId, int quantity);
}
