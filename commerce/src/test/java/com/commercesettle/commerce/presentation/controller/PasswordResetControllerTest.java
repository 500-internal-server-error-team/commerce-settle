package com.commercesettle.commerce.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.commerce.application.exception.AuthChangeUnconfirmedException;
import com.commercesettle.commerce.application.exception.AuthRateLimitedException;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.service.PasswordResetService;
import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(controllers = PasswordResetController.class)
class PasswordResetControllerTest {
  private static final String REQUEST =
      "{\"actorKind\":\"SELLER\",\"email\":\"seller@example.com\"}";
  private static final String TOKEN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
  private static final String RESET =
      "{\"actorKind\":\"BUYER\",\"token\":\"" + TOKEN + "\",\"newPassword\":\"new-password-1\"}";

  @Autowired private MockMvc mvc;
  @MockitoBean private PasswordResetService passwordResets;

  @Test
  @DisplayName("복구 요청은 계정 존재 여부와 관계없는 같은 안내를 200으로 반환한다")
  void acceptsResetRequest() throws Exception {
    send("/api/password-reset-requests", REQUEST)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value(PasswordResetController.ACCEPTED.message()))
        .andExpect(jsonPath("$.token").doesNotExist());
    verify(passwordResets).request(ActorKind.SELLER, "seller@example.com");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"actorKind\":\"ADMIN\",\"email\":\"seller@example.com\"}",
        "{\"actorKind\":\"SELLER\",\"email\":\"not-an-email\"}",
        "{\"email\":\"seller@example.com\"}"
      })
  @DisplayName("계정 종류나 이메일 형식이 틀리면 서비스를 부르지 않고 400이다")
  void rejectsInvalidResetRequest(String body) throws Exception {
    send("/api/password-reset-requests", body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(passwordResets);
  }

  @Test
  @DisplayName("복구 요청 제한은 429와 Retry-After다")
  void mapsRateLimit() throws Exception {
    willThrow(new AuthRateLimitedException(3600)).given(passwordResets).request(any(), anyString());

    send("/api/password-reset-requests", REQUEST)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "3600"))
        .andExpect(jsonPath("$.code").value("COMMON_RATE_LIMITED"));
  }

  @Test
  @DisplayName("재설정에 성공하면 본문 없는 204다")
  void resetsPassword() throws Exception {
    send("/api/password-resets", RESET).andExpect(status().isNoContent());
    verify(passwordResets).reset(ActorKind.BUYER, TOKEN, "new-password-1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"actorKind\":\"BUYER\",\"token\":\"short\",\"newPassword\":\"new-password-1\"}",
        "{\"actorKind\":\"BUYER\",\"token\":\"" + TOKEN + "\",\"newPassword\":\"short\"}",
        "{\"actorKind\":\"BUYER\",\"newPassword\":\"new-password-1\"}"
      })
  @DisplayName("토큰이나 새 비밀번호 길이가 맞지 않으면 서비스를 부르지 않고 400이다")
  void rejectsInvalidReset(String body) throws Exception {
    send("/api/password-resets", body).andExpect(status().isBadRequest());
    verifyNoInteractions(passwordResets);
  }

  @Test
  @DisplayName("쓸 수 없는 토큰은 사유를 구분하지 않는 401이다")
  void mapsUnusableToken() throws Exception {
    willThrow(new AuthenticationFailedException())
        .given(passwordResets)
        .reset(any(), anyString(), anyString());

    send("/api/password-resets", RESET)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("변경 결과를 확정하지 못하면 재전송하지 말라는 안내와 503이다")
  void mapsUnconfirmedChange() throws Exception {
    willThrow(new AuthChangeUnconfirmedException(UUID.randomUUID(), new IllegalStateException()))
        .given(passwordResets)
        .reset(any(), anyString(), anyString());

    send("/api/password-resets", RESET)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("COMMON_TEMPORARILY_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.detail")
                .value("변경 결과를 확인할 수 없습니다. 같은 요청을 다시 보내지 말고 새 비밀번호로 로그인해 확인해 주세요."));
  }

  private ResultActions send(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
