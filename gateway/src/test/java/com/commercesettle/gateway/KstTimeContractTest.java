package com.commercesettle.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class KstTimeContractTest {
  @ParameterizedTest
  @ValueSource(strings = {"gateway", "commerce", "payment", "settlement", "finance-mock"})
  void preservesTheInstantAndMicrosecondsAcrossKstDayAndMonthBoundaries(String service)
      throws IOException {
    var properties =
        new YamlPropertySourceLoader()
            .load(
                "service", new ClassPathResource("time-contract/" + service + "/application.yml"));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withInitializer(
            context -> properties.forEach(context.getEnvironment().getPropertySources()::addLast))
        .run(
            context -> {
              ObjectMapper mapper = context.getBean(ObjectMapper.class);
              assertRoundTrip(
                  mapper, "2026-10-28T14:59:59.999999Z", "2026-10-28T23:59:59.999999+09:00");
              assertRoundTrip(
                  mapper, "2026-10-31T15:00:00.123456Z", "2026-11-01T00:00:00.123456+09:00");
              assertRoundTrip(
                  mapper, "2026-11-01T00:00:00.123456+09:00", "2026-11-01T00:00:00.123456+09:00");
              assertThatThrownBy(
                      () -> mapper.readValue("\"2026-11-01T00:00:00\"", OffsetDateTime.class))
                  .isInstanceOf(JsonProcessingException.class);
            });
  }

  private static void assertRoundTrip(ObjectMapper mapper, String input, String expected)
      throws JsonProcessingException {
    OffsetDateTime value = mapper.readValue("\"" + input + "\"", OffsetDateTime.class);
    assertThat(value.toInstant()).isEqualTo(Instant.parse(input));
    assertThat(value.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    assertThat(mapper.writeValueAsString(value)).isEqualTo("\"" + expected + "\"");
    assertThat(mapper.readValue(mapper.writeValueAsString(value), OffsetDateTime.class).toInstant())
        .isEqualTo(value.toInstant());
  }
}
