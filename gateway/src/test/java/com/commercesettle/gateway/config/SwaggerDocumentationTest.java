package com.commercesettle.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SwaggerDocumentationTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ClassPathResource contract = new ClassPathResource("contracts/openapi.json");

  @Test
  @DisplayName("서비스의 실제 명세에 canonical 사용자 인증을 보완하고 공개 요청은 인증 없이 둔다")
  void enrichesProtectedAndPublicOperations() throws Exception {
    final var documentation =
        new SwaggerDocumentation(mapper, ApiOperations.load(mapper, contract), contract);
    final var body =
        """
        {"openapi":"3.1.0","paths":{
          "/api/orders":{"post":{"summary":"order"}},
          "/api/buyer-sessions":{"post":{}},
          "/internal/probe":{"get":{}}
        },"components":{"schemas":{"Order":{"type":"object"}}}}
        """;

    final var result = mapper.readTree(documentation.enrich("commerce", body));

    assertThat(result.at("/components/securitySchemes/accountSession/scheme").asText())
        .isEqualTo("bearer");
    assertThat(result.path("paths").path("/api/orders").path("post").path("security"))
        .isEqualTo(mapper.readTree("[{\"accountSession\":[]}]"));
    assertThat(result.path("paths").path("/api/buyer-sessions").path("post").path("security"))
        .isEqualTo(mapper.readTree("[]"));
    assertThat(result.path("paths").path("/internal/probe").path("get").has("security")).isFalse();
    assertThat(result.at("/components/schemas/Order/type").asText()).isEqualTo("object");
  }

  @Test
  @DisplayName("상류 오류 본문을 Swagger 명세로 바꾸지 않는다")
  void preservesUpstreamProblems() throws Exception {
    final var documentation =
        new SwaggerDocumentation(mapper, ApiOperations.load(mapper, contract), contract);
    final var problem = "{\"status\":503,\"code\":\"COMMON_TEMPORARILY_UNAVAILABLE\"}";

    assertThat(documentation.enrich("commerce", problem)).isEqualTo(problem);
    assertThat(documentation.enrich("commerce", null)).isNull();
  }
}
