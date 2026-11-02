package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.OrderAdmissionState.Sample;
import java.util.List;

public interface AdmissionMetrics {
  List<Sample> read();
}
