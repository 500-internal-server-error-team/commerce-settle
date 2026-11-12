package com.commercesettle.commerce.presentation.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

  @ParameterizedTest
  @CsvSource({"23505,409,COMMON_VERSION_CONFLICT", "23514,500,COMMON_INTERNAL_ERROR"})
  @DisplayName("유일 제약 경합은 409로 알리고 CHECK 등 다른 무결성 위반은 결함으로 500을 유지한다")
  void classifiesUniqueViolationAsConflict(String state, int status, String code) {
    var handler =
        new CommerceExceptionHandler(new StaticListableBeanFactory().getBeanProvider(Tracer.class));
    var error =
        new DataIntegrityViolationException(
            "private-query", new SQLException("private-detail", state));
    var response =
        handler.integrityViolation(
            error, new ServletWebRequest(new MockHttpServletRequest("POST", "/api/orders")));

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    var problem = (ProblemDetail) response.getBody();
    assertThat(problem.getProperties().get("code")).isEqualTo(code);
    assertThat(problem.getDetail()).doesNotContain("private-query", "private-detail");
  }
}
