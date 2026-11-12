package com.commercesettle.commerce.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.commerce.application.service.BuyerAddressService;
import com.commercesettle.commerce.application.service.BuyerAddressService.StoredAddress;
import com.commercesettle.commerce.domain.exception.BuyerAddressNotFoundException;
import com.commercesettle.commerce.domain.exception.DefaultAddressConflictException;
import com.commercesettle.commerce.domain.model.ShippingAddress;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(BuyerAddressController.class)
class BuyerAddressControllerTest {
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID ADDRESS = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final String ADDRESSES = "/api/buyers/me/addresses";
  private static final String KEY = "key-12345678";
  private static final ShippingAddress HOME =
      new ShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "");
  private static final String VALID_ADDRESS =
      """
      {"recipient":"홍길동","phone":"010-1234-5678","postalCode":"06236",
       "addressLine1":"서울 강남구 테헤란로 1","addressLine2":"","isDefault":false}
      """;

  @Autowired MockMvc mvc;
  @MockitoBean BuyerAddressService addresses;

  @Test
  @DisplayName("주소 등록은 201과 Location, 복호화한 주소와 기본 여부를 반환한다")
  void registersAddress() throws Exception {
    given(addresses.register(BUYER, KEY, HOME, false))
        .willReturn(new StoredAddress(ADDRESS, HOME, true, 0));

    mvc.perform(asBuyer(post(ADDRESSES)).header("Idempotency-Key", KEY).content(VALID_ADDRESS))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", ADDRESSES + "/" + ADDRESS))
        .andExpect(jsonPath("$.id").value(ADDRESS.toString()))
        .andExpect(jsonPath("$.recipient").value("홍길동"))
        .andExpect(jsonPath("$.addressLine2").value(""))
        .andExpect(jsonPath("$.isDefault").value(true))
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  @DisplayName("판매자 신원으로 주소록을 호출하면 403이다")
  void rejectsSellerIdentity() throws Exception {
    mvc.perform(
            get(ADDRESSES)
                .header(AuthenticatedActorResolver.ACTOR_KIND, "SELLER")
                .header(AuthenticatedActorResolver.ACTOR_ID, BUYER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    verifyNoInteractions(addresses);
  }

  @Test
  @DisplayName("신원 헤더가 없으면 401이다")
  void rejectsMissingIdentity() throws Exception {
    mvc.perform(get(ADDRESSES))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(addresses);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"recipient\":\"\",\"phone\":\"010\",\"postalCode\":\"1\",\"addressLine1\":\"a\",\"addressLine2\":\"\",\"isDefault\":false}",
        "{\"recipient\":\"a\",\"phone\":\"010\",\"postalCode\":\"1\",\"addressLine1\":\"a\",\"isDefault\":false}",
        "{\"recipient\":\"a\",\"phone\":\"010\",\"postalCode\":\"1\",\"addressLine1\":\"a\",\"addressLine2\":\"\"}",
        "{\"recipient\":\"a\",\"phone\":\"010\",\"postalCode\":\"1\",\"addressLine1\":\"a\",\"addressLine2\":\"\",\"isDefault\":false,\"buyerId\":\"x\"}"
      })
  @DisplayName("빈 값·누락·허용하지 않는 필드는 400이다")
  void rejectsInvalidBody(String body) throws Exception {
    mvc.perform(asBuyer(post(ADDRESSES)).header("Idempotency-Key", KEY).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(addresses);
  }

  @Test
  @DisplayName("타인·삭제 주소 변경은 404다")
  void rejectsUnknownAddress() throws Exception {
    given(addresses.change(eq(BUYER), eq(ADDRESS), anyString(), any(), anyBoolean(), anyLong()))
        .willThrow(new BuyerAddressNotFoundException(ADDRESS));

    mvc.perform(
            asBuyer(put(ADDRESSES + "/" + ADDRESS))
                .header("Idempotency-Key", KEY)
                .content(
                    VALID_ADDRESS.replace(
                        "\"isDefault\":false", "\"isDefault\":false,\"expectedVersion\":0")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("기본 주소를 동시에 바꿔 충돌하면 409다")
  void reportsConcurrentDefaultChange() throws Exception {
    given(addresses.register(BUYER, KEY, HOME, true))
        .willThrow(new DefaultAddressConflictException());

    mvc.perform(
            asBuyer(post(ADDRESSES))
                .header("Idempotency-Key", KEY)
                .content(VALID_ADDRESS.replace("\"isDefault\":false", "\"isDefault\":true")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("삭제는 expectedVersion이 필요하고 성공하면 204다")
  void deletesAddress() throws Exception {
    mvc.perform(asBuyer(delete(ADDRESSES + "/" + ADDRESS)).header("Idempotency-Key", KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("expectedVersion"));

    mvc.perform(
            asBuyer(delete(ADDRESSES + "/" + ADDRESS))
                .header("Idempotency-Key", KEY)
                .param("expectedVersion", "0"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("삭제 대상이 없으면 404다")
  void rejectsDeletingUnknownAddress() throws Exception {
    willThrow(new BuyerAddressNotFoundException(ADDRESS))
        .given(addresses)
        .delete(BUYER, ADDRESS, KEY, 0);

    mvc.perform(
            asBuyer(delete(ADDRESSES + "/" + ADDRESS))
                .header("Idempotency-Key", KEY)
                .param("expectedVersion", "0"))
        .andExpect(status().isNotFound());
  }

  private static MockHttpServletRequestBuilder asBuyer(MockHttpServletRequestBuilder request) {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .header(AuthenticatedActorResolver.ACTOR_KIND, "BUYER")
        .header(AuthenticatedActorResolver.ACTOR_ID, BUYER.toString());
  }
}
