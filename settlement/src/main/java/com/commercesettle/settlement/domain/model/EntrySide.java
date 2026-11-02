package com.commercesettle.settlement.domain.model;

/** 원장의 측. SOURCE는 돈이 온 곳, ALLOCATION은 돈이 나뉘는 곳이며 각 측 합계는 정산 기준액 B다. */
public enum EntrySide {
  SOURCE,
  ALLOCATION
}
