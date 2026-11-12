package com.commercesettle.settlement.domain.model;

/** 원장 leg와 그 leg가 속한 측. */
public enum EntryLeg {
  EXTERNAL(EntrySide.SOURCE),
  MILEAGE(EntrySide.SOURCE),
  PLATFORM_COUPON(EntrySide.SOURCE),
  SELLER(EntrySide.ALLOCATION),
  FEE(EntrySide.ALLOCATION);

  private final EntrySide side;

  EntryLeg(EntrySide side) {
    this.side = side;
  }

  public EntrySide side() {
    return side;
  }
}
