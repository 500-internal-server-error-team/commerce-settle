package com.commercesettle.settlement.domain.model;

public enum SettlementItemStatus {
  WAITING_CONFIRMATION,
  PAYABLE,
  REFUND_RESERVED,
  REFUNDED,
  PAYOUT_RESERVED,
  PAID;

  public boolean canTransitionTo(SettlementItemStatus next) {
    return switch (this) {
      case WAITING_CONFIRMATION -> next == PAYABLE || next == REFUND_RESERVED;
      case PAYABLE -> next == REFUND_RESERVED || next == PAYOUT_RESERVED;
      case REFUND_RESERVED -> next == REFUNDED || next == WAITING_CONFIRMATION || next == PAYABLE;
      case PAYOUT_RESERVED -> next == PAID;
      case REFUNDED, PAID -> false;
    };
  }
}
