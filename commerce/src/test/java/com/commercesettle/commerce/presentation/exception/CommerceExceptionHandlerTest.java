package com.commercesettle.commerce.presentation.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class CommerceExceptionHandlerTest {
  @ParameterizedTest
  @CsvSource({
    "55P03,503,COMMON_TEMPORARILY_UNAVAILABLE",
    "57014,503,COMMON_TEMPORARILY_UNAVAILABLE",
    "42P01,500,COMMON_INTERNAL_ERROR"
  })
  @DisplayName("JDBC의 잠금·쿼리 대기 초과만 503으로 분류하고 SQL 결함은 500을 유지한다")
  void classifiesPostgresTimeoutWithoutHidingSqlDefects(String state, int status, String code) {
    var handler =
        new CommerceExceptionHandler(new StaticListableBeanFactory().getBeanProvider(Tracer.class));
    var error =
        new UncategorizedSQLException(
            "test", "private-query", new SQLException("private-detail", state));
    var response =
        handler.uncategorizedStorage(
            error, new ServletWebRequest(new MockHttpServletRequest("POST", "/api/notifications")));

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    var problem = (ProblemDetail) response.getBody();
    assertThat(problem.getProperties().get("code")).isEqualTo(code);
    assertThat(problem.getDetail()).doesNotContain("private-query", "private-detail");
  }
}
