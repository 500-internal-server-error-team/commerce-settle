package com.commercesettle.settlement.infrastructure.client;

import com.commercesettle.internalauth.InternalCredentials;
import com.commercesettle.settlement.application.exception.DemoRunException;
import com.commercesettle.settlement.application.port.DemoRunSource;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpDemoRunSource implements DemoRunSource {
  private final RestClient client;
  private final String authorization;

  public HttpDemoRunSource(RestClient client, String credential) {
    this.client = client;
    this.authorization =
        "Bearer " + InternalCredentials.requireFormat("service:settlement", credential);
  }

  @Override
  public Source read(UUID id) {
    try {
      var source =
          client
              .get()
              .uri("/internal/demo-runs/{runId}/settlement-context", id)
              .header("Authorization", authorization)
              .retrieve()
              .body(Source.class);
      if (source == null
          || !id.equals(source.id())
          || source.sellerId() == null
          || source.orderIds() == null
          || source.payableOrderIds() == null
          || source.refundedOrderIds() == null)
        throw new DemoRunException("DEMO_SOURCE_UNAVAILABLE", "시연 회차 응답을 확인할 수 없습니다.");
      return source;
    } catch (RestClientException e) {
      throw new DemoRunException("DEMO_SOURCE_UNAVAILABLE", "Commerce 시연 회차를 조회할 수 없습니다.");
    }
  }
}
