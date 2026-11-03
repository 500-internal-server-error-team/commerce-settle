package com.commercesettle.commerce.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.commerce.application.service.SellerProfileService;
import com.commercesettle.commerce.application.service.SellerProfileService.SettlementContext;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.AccountStatus;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.SellerAccount;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SellerProfileController.class)
class SellerProfileControllerTest {
  private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final String PROFILE = "/api/sellers/me";
  private static final String KEY = "key-12345678";
  private static final String UPDATE =
      """
      {"displayName":"새 상점","payoutAccountRef":"account-new","expectedVersion":0}
      """;

  @Autowired MockMvc mvc;
  @MockitoBean SellerProfileService sellers;

  @Test
  @DisplayName("본인 판매자 정보는 판매 상태·버전·수취 계정을 반환한다")
  void returnsProfile() throws Exception {
    SellerAccount profile = seller("상점", "account-a", 4);
    given(sellers.profile(SELLER)).willReturn(profile);

    mvc.perform(asSeller(get(PROFILE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(SELLER.toString()))
        .andExpect(jsonPath("$.actorKind").value("SELLER"))
        .andExpect(jsonPath("$.displayName").value("상점"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.payoutAccountRef").value("account-a"));
  }

  @Test
  @DisplayName("구매자 신원으로 판매자 정보를 호출하면 403이다")
  void rejectsBuyerIdentity() throws Exception {
    mvc.perform(
            get(PROFILE)
                .header(AuthenticatedActorResolver.ACTOR_KIND, "BUYER")
                .header(AuthenticatedActorResolver.ACTOR_ID, SELLER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    verifyNoInteractions(sellers);
  }

  @Test
  @DisplayName("변경은 멱등 키와 기대 버전을 넘기고 바뀐 프로필을 반환한다")
  void changesProfile() throws Exception {
    SellerAccount changed = seller("새 상점", "account-new", 1);
    given(sellers.change(SELLER, KEY, "새 상점", "account-new", 0)).willReturn(changed);

    mvc.perform(asSeller(put(PROFILE)).header("Idempotency-Key", KEY).content(UPDATE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("새 상점"))
        .andExpect(jsonPath("$.payoutAccountRef").value("account-new"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  @DisplayName("멱등 키·기대 버전이 없으면 400, 버전 충돌은 409다")
  void validatesChange() throws Exception {
    mvc.perform(asSeller(put(PROFILE)).content(UPDATE)).andExpect(status().isBadRequest());
    mvc.perform(
            asSeller(put(PROFILE))
                .header("Idempotency-Key", KEY)
                .content(UPDATE.replace(",\"expectedVersion\":0", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));

    given(sellers.change(SELLER, KEY, "새 상점", "account-new", 0))
        .willThrow(new VersionConflictException("seller profile", 0, 1));
    mvc.perform(asSeller(put(PROFILE)).header("Idempotency-Key", KEY).content(UPDATE))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("지급 컨텍스트는 명세 필드로 반환하고 없는 상품은 404다")
  void returnsSettlementContext() throws Exception {
    given(sellers.settlementContext(PRODUCT))
        .willReturn(
            new SettlementContext(PRODUCT, SELLER, "account-a", 4, AccountStatus.SUSPENDED));

    mvc.perform(get("/internal/products/" + PRODUCT + "/settlement-context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value(PRODUCT.toString()))
        .andExpect(jsonPath("$.sellerId").value(SELLER.toString()))
        .andExpect(jsonPath("$.payoutAccountRef").value("account-a"))
        .andExpect(jsonPath("$.sellerVersion").value(4))
        .andExpect(jsonPath("$.sellerStatus").value("SUSPENDED"));

    UUID missing = UUID.randomUUID();
    given(sellers.settlementContext(missing)).willThrow(new ProductNotFoundException(missing));
    mvc.perform(get("/internal/products/" + missing + "/settlement-context"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));
  }

  private static SellerAccount seller(String displayName, String payoutAccountRef, long version) {
    SellerAccount seller = mock(SellerAccount.class);
    given(seller.getId()).willReturn(SELLER);
    given(seller.actorKind()).willReturn(ActorKind.SELLER);
    given(seller.getDisplayName()).willReturn(displayName);
    given(seller.getStatus()).willReturn(AccountStatus.ACTIVE);
    given(seller.getVersion()).willReturn(version);
    given(seller.getPayoutAccountRef()).willReturn(payoutAccountRef);
    return seller;
  }

  private static MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder request) {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .header(AuthenticatedActorResolver.ACTOR_KIND, "SELLER")
        .header(AuthenticatedActorResolver.ACTOR_ID, SELLER.toString());
  }
}
