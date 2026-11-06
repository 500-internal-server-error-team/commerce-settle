package com.commercesettle.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springdoc.core.properties.SwaggerUiConfigParameters;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webflux.ui.SwaggerIndexPageTransformer;
import org.springdoc.webflux.ui.SwaggerIndexTransformer;
import org.springdoc.webflux.ui.SwaggerWelcomeCommon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
public class SwaggerSessionConfiguration {
  @Bean
  SwaggerDocumentation swaggerDocumentation(
      ObjectMapper mapper,
      ApiOperations operations,
      @Value("classpath:contracts/openapi.json") Resource contract)
      throws IOException {
    return new SwaggerDocumentation(mapper, operations, contract);
  }

  @Bean
  SwaggerIndexTransformer swaggerIndexTransformer(
      SwaggerUiConfigProperties properties,
      SwaggerUiOAuthProperties oauth,
      SwaggerWelcomeCommon welcome,
      ObjectMapperProvider mapper,
      SwaggerDocumentation documentation,
      @Value("classpath:swagger-session.js") Resource client)
      throws IOException {
    final String script;
    try (var input = client.getInputStream()) {
      script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    final var configuration = documentation.clientConfiguration();
    return new SwaggerIndexPageTransformer(properties, oauth, welcome, mapper) {
      @Override
      protected String defaultTransformations(
          SwaggerUiConfigParameters parameters, InputStream input) throws IOException {
        final var initializer = super.defaultTransformations(parameters, input);
        final var start = "window.onload = function() {";
        final var bundle = "SwaggerUIBundle({";
        if (!initializer.contains(start) || !initializer.contains(bundle)) {
          throw new IllegalStateException("Unsupported Swagger UI initializer");
        }
        return initializer
            .replace(
                start,
                start
                    + "\n"
                    + script
                    + "\nconst session = createSwaggerSession("
                    + configuration
                    + ");")
            .replace(
                bundle,
                bundle
                    + "\nrequestInterceptor: session.request,"
                    + "\nonComplete: function() { session.attach(window.ui); },");
      }
    };
  }
}
