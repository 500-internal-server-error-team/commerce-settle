package com.commercesettle.commerce.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Commerce")
                .version("0.0.1")
                .description("Commerce API specification."))
        .servers(List.of(new Server().url("/")));
  }

  /** nullable은 type과 별개로 enum의 허용 값에도 반영해야 JSON Schema에서 null을 허용한다. */
  @Bean
  public OpenApiCustomizer nullableEnumValues() {
    return api -> {
      if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
        api.getComponents()
            .getSchemas()
            .values()
            .forEach(OpenApiConfiguration::includeNullEnumValue);
      }
    };
  }

  private static void includeNullEnumValue(Schema<?> schema) {
    boolean nullable =
        Boolean.TRUE.equals(schema.getNullable())
            || (schema.getTypes() != null && schema.getTypes().contains("null"));
    if (nullable && schema.getEnum() != null && !schema.getEnum().contains(null)) {
      schema.addEnumItemObject(null);
    }
    if (schema.getProperties() != null) {
      schema.getProperties().values().forEach(OpenApiConfiguration::includeNullEnumValue);
    }
  }
}
