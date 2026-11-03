package com.commercesettle.settlement.infrastructure.client;

import com.commercesettle.internalauth.InternalCredentials;
import com.commercesettle.settlement.application.exception.ProductSettlementContextUnavailableException;
import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 커머스 {@code GET /internal/products/{productId}/settlement-context}의 HTTP 구현. 모든 요청에 커머스용 {@code
 * service:settlement} 내부 호출 자격(ADR 0010)을 붙인다.
 *
 * <p>커머스가 상품 부재 코드로 답한 404만 결과에서 빼 지급 전 검증에서 거절되게 한다. 커머스는 명세에 없는 경로도 같은 코드로 답하므로 경로 불일치도 여기에 포함된다.
 * 코드 없는 404 등 그 밖의 오류·자격 거절·타임아웃·응답 불일치는 조회 실패 예외로 알린다. 판매자 상태는 ACTIVE일 때만 지급 가능이며, 모르는 값도 지급 불가로
 * 본다.
 */
public class HttpProductSettlementContextReader implements ProductSettlementContextReader {
  private static final String SENDER = "service:settlement";
  private static final String NOT_FOUND_CODE = "COMMON_RESOURCE_NOT_FOUND";

  private final RestClient client;
  private final String authorization;

  /**
   * @param credential 커머스가 {@code service:settlement}로 확인하는 자격. 없거나 43자 미만이면 기동하지 않는다.
   */
  public HttpProductSettlementContextReader(RestClient client, String credential) {
    this.client = client;
    this.authorization = "Bearer " + InternalCredentials.requireFormat(SENDER, credential);
  }

  @Override
  public List<ProductSettlementContext> read(UUID sellerId, Set<UUID> productIds) {
    List<ProductSettlementContext> contexts = new ArrayList<>();
    for (UUID productId : productIds) {
      Context body = fetch(productId);
      if (body == null) {
        continue;
      }
      if (!productId.equals(body.productId())
          || body.sellerId() == null
          || body.sellerVersion() == null) {
        throw new ProductSettlementContextUnavailableException(
            "settlement context does not match the requested product");
      }
      contexts.add(
          new ProductSettlementContext(
              body.productId(),
              body.sellerId(),
              "ACTIVE".equals(body.sellerStatus()),
              body.payoutAccountRef(),
              body.sellerVersion()));
    }
    return contexts;
  }

  // 상품이 없다는 커머스의 답만 null이다.
  private Context fetch(UUID productId) {
    try {
      Context body =
          client
              .get()
              .uri("/internal/products/{productId}/settlement-context", productId)
              .header(HttpHeaders.AUTHORIZATION, authorization)
              .retrieve()
              .body(Context.class);
      if (body == null) {
        throw new ProductSettlementContextUnavailableException("empty settlement context");
      }
      return body;
    } catch (HttpClientErrorException.NotFound e) {
      if (NOT_FOUND_CODE.equals(problemCode(e))) {
        return null;
      }
      throw new ProductSettlementContextUnavailableException(
          "HTTP 404 without resource-not-found code", e);
    } catch (RestClientException e) {
      throw new ProductSettlementContextUnavailableException(e.getClass().getSimpleName(), e);
    }
  }

  private static String problemCode(HttpClientErrorException e) {
    try {
      Problem problem = e.getResponseBodyAs(Problem.class);
      return problem == null ? null : problem.code();
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Context(
      UUID productId,
      UUID sellerId,
      String payoutAccountRef,
      Long sellerVersion,
      String sellerStatus) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Problem(String code) {}
}
