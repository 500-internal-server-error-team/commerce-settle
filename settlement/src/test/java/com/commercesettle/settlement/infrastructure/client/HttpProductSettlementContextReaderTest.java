package com.commercesettle.settlement.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.commercesettle.settlement.application.exception.ProductSettlementContextUnavailableException;
import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import com.commercesettle.settlement.infrastructure.config.CommerceClientConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class HttpProductSettlementContextReaderTest {
  private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final String CREDENTIAL = "commerce-settlement-credential-0123456789abcdef";
  private static final String URL =
      "http://commerce/internal/products/" + PRODUCT + "/settlement-context";

  private MockRestServiceServer server;
  private HttpProductSettlementContextReader reader;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://commerce");
    server = MockRestServiceServer.bindTo(builder).build();
    reader = new HttpProductSettlementContextReader(builder.build(), CREDENTIAL);
  }

  @Test
  void outboundContextUsesCommerceReceiverCredentialWhenBothDirectionsAreConfigured() {
    String inbound = "A".repeat(43), outbound = "B".repeat(43);
    new ApplicationContextRunner()
        .withInitializer(
            context -> {
              context
                  .getBeanFactory()
                  .setConversionService(ApplicationConversionService.getSharedInstance());
              try {
                for (var source :
                    new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml")))
                  context.getEnvironment().getPropertySources().addLast(source);
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
              context
                  .getEnvironment()
                  .getPropertySources()
                  .addFirst(
                      new SystemEnvironmentPropertySource(
                          "directions",
                          Map.of(
                              "SETTLEMENT_COMMERCE_CREDENTIAL",
                              inbound,
                              "COMMERCE_SETTLEMENT_CREDENTIAL",
                              outbound)));
            })
        .withUserConfiguration(CommerceClientConfiguration.class)
        .withBean(
            RestClient.Builder.class,
            () ->
                RestClient.builder()
                    .requestInterceptor(
                        (request, body, execution) -> {
                          assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                              .isEqualTo("Bearer " + outbound);
                          var response =
                              new MockClientHttpResponse(
                                  ("{\"productId\":\""
                                          + PRODUCT
                                          + "\",\"sellerId\":\""
                                          + SELLER
                                          + "\",\"payoutAccountRef\":\"seller-account\",\"sellerVersion\":3,\"sellerStatus\":\"ACTIVE\"}")
                                      .getBytes(StandardCharsets.UTF_8),
                                  HttpStatus.OK);
                          response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                          return response;
                        }))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(ProductSettlementContextReader.class)
                          .read(SELLER, Set.of(PRODUCT)))
                  .containsExactly(
                      new ProductSettlementContext(PRODUCT, SELLER, true, "seller-account", 3));
            });
  }

  @Test
  @DisplayName("서비스 자격을 붙여 조회하고 ACTIVE 판매자의 계정·버전을 지급 가능 컨텍스트로 돌려준다")
  void readsActiveSellerContext() {
    server
        .expect(requestTo(URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
        .andRespond(context(PRODUCT, "ACTIVE"));

    List<ProductSettlementContext> contexts = reader.read(SELLER, Set.of(PRODUCT));

    assertThat(contexts)
        .containsExactly(new ProductSettlementContext(PRODUCT, SELLER, true, "seller-account", 3));
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUSPENDED", "DELETED", "UNKNOWN_STATUS"})
  @DisplayName("ACTIVE가 아니거나 모르는 판매자 상태는 지급 불가로 돌려준다")
  void treatsOtherStatusesAsNotAllowed(String status) {
    server.expect(requestTo(URL)).andRespond(context(PRODUCT, status));

    assertThat(reader.read(SELLER, Set.of(PRODUCT)))
        .singleElement()
        .extracting(ProductSettlementContext::payoutAllowed)
        .isEqualTo(false);
  }

  @Test
  @DisplayName("상품 부재 코드의 404만 결과에서 뺀다")
  void omitsMissingProduct() {
    server
        .expect(requestTo(URL))
        .andRespond(problem(HttpStatus.NOT_FOUND, "COMMON_RESOURCE_NOT_FOUND"));

    assertThat(reader.read(SELLER, Set.of(PRODUCT))).isEmpty();
  }

  @Test
  @DisplayName("상품 부재 코드가 없는 404는 커머스의 답으로 보지 않고 조회 실패다")
  void rejectsNotFoundWithoutCode() {
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> reader.read(SELLER, Set.of(PRODUCT)))
        .isInstanceOf(ProductSettlementContextUnavailableException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500, 503, 504})
  @DisplayName("자격 거절·서버 오류는 판매자를 지급 가능으로 보지 않고 조회 실패로 알린다")
  void rejectsFailures(int status) {
    server
        .expect(requestTo(URL))
        .andRespond(problem(HttpStatus.valueOf(status), "COMMON_INTERNAL_ERROR"));

    assertThatThrownBy(() -> reader.read(SELLER, Set.of(PRODUCT)))
        .isInstanceOf(ProductSettlementContextUnavailableException.class);
  }

  @Test
  @DisplayName("요청과 다른 상품의 응답은 믿지 않는다")
  void rejectsMismatchedProduct() {
    server.expect(requestTo(URL)).andRespond(context(UUID.randomUUID(), "ACTIVE"));

    assertThatThrownBy(() -> reader.read(SELLER, Set.of(PRODUCT)))
        .isInstanceOf(ProductSettlementContextUnavailableException.class);
  }

  @Test
  @DisplayName("필수 판매자 버전이 빠진 응답은 0으로 보지 않고 조회 실패다")
  void rejectsMissingSellerVersion() {
    server
        .expect(requestTo(URL))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                    {"productId":"%s","sellerId":"%s","payoutAccountRef":"seller-account",
                     "sellerStatus":"ACTIVE"}
                    """
                        .formatted(PRODUCT, SELLER)));

    assertThatThrownBy(() -> reader.read(SELLER, Set.of(PRODUCT)))
        .isInstanceOf(ProductSettlementContextUnavailableException.class);
  }

  @Test
  @DisplayName("자격이 없거나 43자 미만이면 만들지 않는다")
  void requiresCredentialFormat() {
    RestClient client = RestClient.create();

    assertThatThrownBy(() -> new HttpProductSettlementContextReader(client, "short"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HttpProductSettlementContextReader(client, null))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ResponseCreator context(UUID productId, String status) {
    return withStatus(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"productId":"%s","sellerId":"%s","payoutAccountRef":"seller-account",
             "sellerVersion":3,"sellerStatus":"%s"}
            """
                .formatted(productId, SELLER, status));
  }

  private static ResponseCreator problem(HttpStatus status, String code) {
    return withStatus(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body("{\"code\":\"%s\"}".formatted(code));
  }
}
