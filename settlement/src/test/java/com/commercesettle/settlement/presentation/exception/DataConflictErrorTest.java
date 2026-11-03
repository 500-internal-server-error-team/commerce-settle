package com.commercesettle.settlement.presentation.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.tracing.Tracer;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 저장 충돌 중 유일 제약 경합과 낙관적 잠금만 409이고, 그 밖의 무결성 위반은 숨기지 않고 500인지 확인한다. */
class DataConflictErrorTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(
                new SettlementExceptionHandler(
                    new DefaultListableBeanFactory().getBeanProvider(Tracer.class)))
            .build();
  }

  @Test
  @DisplayName("유일 제약 위반(23505)은 다시 요청하면 최신 상태로 판정되므로 409 버전 충돌이다")
  void uniqueViolationIsVersionConflict() throws Exception {
    mvc.perform(get("/integrity/23505"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"23502", "23514", "22001"})
  @DisplayName("NOT NULL·CHECK·길이 초과 위반은 버전 충돌로 숨기지 않고 500이다")
  void otherIntegrityViolationsAreInternalErrors(String sqlState) throws Exception {
    mvc.perform(get("/integrity/{state}", sqlState))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
  }

  @Test
  @DisplayName("SQL 원인이 없는 무결성 위반도 500이다")
  void integrityViolationWithoutSqlCauseIsInternalError() throws Exception {
    mvc.perform(get("/integrity-without-cause"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
  }

  @Test
  @DisplayName("낙관적 잠금 실패는 409 버전 충돌이다")
  void optimisticLockIsVersionConflict() throws Exception {
    mvc.perform(get("/optimistic-lock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
  }

  @RestController
  static class Endpoints {
    @GetMapping("/integrity/{state}")
    void integrity(@PathVariable String state) {
      throw new DataIntegrityViolationException(
          "constraint", new RuntimeException("wrapped", new SQLException("violation", state)));
    }

    @GetMapping("/integrity-without-cause")
    void integrityWithoutCause() {
      throw new DataIntegrityViolationException("constraint");
    }

    @GetMapping("/optimistic-lock")
    void optimisticLock() {
      throw new ObjectOptimisticLockingFailureException(Object.class, "id");
    }
  }
}
