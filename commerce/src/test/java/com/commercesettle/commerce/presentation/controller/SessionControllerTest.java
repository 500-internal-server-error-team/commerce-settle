package com.commercesettle.commerce.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.exception.LoginRateLimitedException;
import com.commercesettle.commerce.application.service.AccountService;
import com.commercesettle.commerce.application.service.SessionService;
import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
import com.commercesettle.commerce.domain.model.AccountStatus;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.SellerAccount;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {SessionController.class, AccountController.class})
class SessionControllerTest {
  private static final String LOGIN =
      "{\"email\":\"seller@example.com\",\"password\":\"password-123\"}";
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String HASH = "a".repeat(64);

  @Autowired private MockMvc mvc;
  @MockitoBean private SessionService sessions;
  @MockitoBean private AccountService accounts;

  @Test
  @DisplayName("로그인 성공은 KST 만료 시각과 계정을 담고 응답을 캐시하지 않는다")
  void returnsSessionWithoutCaching() throws Exception {
    SellerAccount account = seller();
    given(sessions.login(ActorKind.SELLER, "seller@example.com", "password-123"))
        .willReturn(
            new SessionService.Login(
                "opaque-token", Instant.parse("2026-10-29T00:30:00Z"), account));

    mvc.perform(post("/api/seller-sessions").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.token").value("opaque-token"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresAt").value("2026-10-29T09:30:00+09:00"))
        .andExpect(jsonPath("$.account.id").value(account.getId().toString()))
        .andExpect(jsonPath("$.account.actorKind").value("SELLER"))
        .andExpect(jsonPath("$.account.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("인증 실패는 사유를 구분하지 않는 401 공통 오류다")
  void mapsAuthenticationFailure() throws Exception {
    given(sessions.login(any(), anyString(), anyString()))
        .willThrow(new AuthenticationFailedException());

    mvc.perform(post("/api/buyer-sessions").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.instance").value("/api/buyer-sessions"));
  }

  @Test
  @DisplayName("요청 제한은 429와 Retry-After로 알린다")
  void mapsRateLimit() throws Exception {
    given(sessions.login(any(), anyString(), anyString()))
        .willThrow(new LoginRateLimitedException(120));

    mvc.perform(post("/api/buyer-sessions").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "120"))
        .andExpect(jsonPath("$.code").value("COMMON_RATE_LIMITED"));
  }

  @Test
  @DisplayName("인증 저장소 장애는 인증 실패가 아닌 503이다")
  void mapsUnavailableStore() throws Exception {
    given(sessions.login(any(), anyString(), anyString()))
        .willThrow(new AuthenticationUnavailableException("down"));

    mvc.perform(post("/api/seller-sessions").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("COMMON_TEMPORARILY_UNAVAILABLE"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"email\":\"seller@example.com\",\"password\":\"short\"}",
        "{\"email\":\"not-an-email\",\"password\":\"password-123\"}",
        "{\"email\":\"seller@example.com\",\"password\":\"password-123\",\"role\":\"SELLER\"}"
      })
  @DisplayName("형식이 틀린 로그인은 제한을 세기 전에 400으로 거절한다")
  void rejectsInvalidLoginBeforeService(String body) throws Exception {
    mvc.perform(post("/api/seller-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(sessions);
  }

  @Test
  @DisplayName("로그아웃은 Gateway가 전달한 신원과 세션 해시로 현재 세션을 지운다")
  void logsOutCurrentSession() throws Exception {
    mvc.perform(delete("/api/sessions/current").headers(identity("SELLER")))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("로그아웃에 세션 해시가 없거나 형식이 틀리면 401이다")
  void rejectsLogoutWithoutSessionHash() throws Exception {
    mvc.perform(
            delete("/api/sessions/current")
                .header("X-Authenticated-Actor-Kind", "SELLER")
                .header("X-Authenticated-Actor-Id", ACTOR_ID.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            delete("/api/sessions/current")
                .header("X-Authenticated-Actor-Kind", "SELLER")
                .header("X-Authenticated-Actor-Id", ACTOR_ID.toString())
                .header(SessionController.SESSION_HASH, "A".repeat(64)))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(sessions);
  }

  @Test
  @DisplayName("신원 헤더가 없거나 알 수 없는 계정 종류면 401이다")
  void rejectsMissingIdentity() throws Exception {
    mvc.perform(get("/api/accounts/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"));
    mvc.perform(get("/api/accounts/me").headers(identity("ADMIN")))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(accounts);
  }

  @Test
  @DisplayName("본인 계정은 신원 헤더의 계정 종류·ID로 조회한다")
  void returnsCurrentAccount() throws Exception {
    SellerAccount account = seller();
    given(accounts.current(ActorKind.SELLER, ACTOR_ID)).willReturn(account);

    mvc.perform(get("/api/accounts/me").headers(identity("SELLER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("판매자"));
  }

  @Test
  @DisplayName("판매자 가입은 201과 계정을 반환하고 중복 이메일은 409다")
  void registersSeller() throws Exception {
    String body =
        "{\"email\":\"seller@example.com\",\"password\":\"password-123\","
            + "\"displayName\":\"판매자\",\"payoutAccountRef\":\"ref\"}";
    SellerAccount account = seller();
    given(accounts.registerSeller("seller@example.com", "password-123", "판매자", "ref"))
        .willReturn(account);

    mvc.perform(post("/api/sellers").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/accounts/me"))
        .andExpect(jsonPath("$.actorKind").value("SELLER"));

    given(accounts.registerSeller(anyString(), anyString(), anyString(), eq("ref")))
        .willThrow(new DuplicateAccountEmailException());
    mvc.perform(post("/api/sellers").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_EMAIL_DUPLICATED"));
  }

  @Test
  @DisplayName("로그아웃 저장소 장애는 삭제 미확인 503이다")
  void mapsLogoutFailure() throws Exception {
    willThrow(new AuthenticationUnavailableException("down"))
        .given(sessions)
        .logout(HASH, ActorKind.BUYER, ACTOR_ID);

    mvc.perform(delete("/api/sessions/current").headers(identity("BUYER")))
        .andExpect(status().isServiceUnavailable());
  }

  private static HttpHeaders identity(String kind) {
    var headers = new HttpHeaders();
    headers.add("X-Authenticated-Actor-Kind", kind);
    headers.add("X-Authenticated-Actor-Id", ACTOR_ID.toString());
    headers.add(SessionController.SESSION_HASH, HASH);
    return headers;
  }

  // 저장 전 엔티티는 version이 없으므로 저장된 계정처럼 응답 필드만 채운다.
  private static SellerAccount seller() {
    SellerAccount account = mock(SellerAccount.class);
    given(account.getId()).willReturn(ACTOR_ID);
    given(account.actorKind()).willReturn(ActorKind.SELLER);
    given(account.getDisplayName()).willReturn("판매자");
    given(account.getStatus()).willReturn(AccountStatus.ACTIVE);
    given(account.getVersion()).willReturn(0L);
    return account;
  }
}
