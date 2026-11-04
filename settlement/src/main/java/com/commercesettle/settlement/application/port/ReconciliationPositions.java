package com.commercesettle.settlement.application.port;

import java.util.Map;

public interface ReconciliationPositions {
  /** offset 미등록과 조회 실패를 구분한다. 실패는 예외, 미등록은 map의 부재다. */
  Map<String, Long> committed();
}
