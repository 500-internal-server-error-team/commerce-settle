package com.commercesettle.commerce.presentation.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.service.ProductCatalogService;
import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import java.time.Instant;
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

@WebMvcTest(SellerProductController.class)
class SellerProductControllerTest {
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final String PRODUCTS = "/api/sellers/me/products";
  private static final String KEY = "key-12345678";
  private static final String VALID_PRODUCT =
      """
      {"name":"키보드","unitPrice":39000,"status":"ON_SALE"}
      """;

  @Autowired MockMvc mvc;
  @MockitoBean ProductCatalogService catalog;

  @Test
  @DisplayName("상품 등록은 201과 Location, 서버가 정한 상품 값을 반환한다")
  void registersProduct() throws Exception {
    given(catalog.register(SELLER, KEY, "키보드", 39_000, ProductStatus.ON_SALE))
        .willReturn(product(ProductStatus.ON_SALE));

    mvc.perform(asSeller(post(PRODUCTS)).header("Idempotency-Key", KEY).content(VALID_PRODUCT))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", PRODUCTS + "/" + PRODUCT))
        .andExpect(jsonPath("$.id").value(PRODUCT.toString()))
        .andExpect(jsonPath("$.sellerId").value(SELLER.toString()))
        .andExpect(jsonPath("$.status").value("ON_SALE"))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.createdAt").value("2026-10-28T10:00:00+09:00"));
  }

  @Test
  @DisplayName("신원 헤더가 없으면 401 공통 오류로 거절한다")
  void rejectsMissingIdentity() throws Exception {
    mvc.perform(
            post(PRODUCTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(VALID_PRODUCT))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code").value("COMMON_AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.instance").value(PRODUCTS));
    verifyNoInteractions(catalog);
  }

  @Test
  @DisplayName("구매자 신원으로 판매자 상품 API를 호출하면 403이다")
  void rejectsBuyerIdentity() throws Exception {
    mvc.perform(
            get(PRODUCTS)
                .header(AuthenticatedSellerResolver.ACTOR_KIND, "BUYER")
                .header(AuthenticatedSellerResolver.ACTOR_ID, SELLER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_ROLE_FORBIDDEN"));
    verifyNoInteractions(catalog);
  }

  @Test
  @DisplayName("멱등키가 없으면 해당 헤더의 필드 오류로 400이다")
  void rejectsMissingIdempotencyKey() throws Exception {
    mvc.perform(asSeller(post(PRODUCTS)).content(VALID_PRODUCT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
  }

  @Test
  @DisplayName("멱등키가 8자보다 짧으면 400이다")
  void rejectsShortIdempotencyKey() throws Exception {
    mvc.perform(asSeller(post(PRODUCTS)).header("Idempotency-Key", "short").content(VALID_PRODUCT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", hasItem("Idempotency-Key")));
    verifyNoInteractions(catalog);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"name\":\"키보드\",\"unitPrice\":0,\"status\":\"ON_SALE\"}",
        "{\"name\":\"키보드\",\"unitPrice\":10000001,\"status\":\"ON_SALE\"}",
        "{\"name\":\"\",\"unitPrice\":1000,\"status\":\"ON_SALE\"}",
        "{\"name\":\"키보드\",\"unitPrice\":1000,\"status\":\"DELETED\"}",
        "{\"name\":\"키보드\",\"unitPrice\":1000}"
      })
  @DisplayName("단가·이름·상태 범위를 벗어난 본문은 필드 오류와 함께 400이다")
  void rejectsInvalidProductBody(String body) throws Exception {
    mvc.perform(asSeller(post(PRODUCTS)).header("Idempotency-Key", KEY).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").exists());
    verifyNoInteractions(catalog);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"name\":\"키보드\",\"unitPrice\":1000,\"status\":\"ON_SALE\",\"sellerId\":\"x\"}",
        "{\"name\":\"키보드\",\"unitPrice\":1000.5,\"status\":\"ON_SALE\"}",
        "{\"name\":\"키보드\",\"unitPrice\":\"1000\",\"status\":\"ON_SALE\"}",
        "{\"name\":123,\"unitPrice\":1000,\"status\":\"ON_SALE\"}"
      })
  @DisplayName("정의되지 않은 필드나 타입이 다른 값은 400이며 입력 값을 응답에 넣지 않는다")
  void rejectsUnknownFieldsAndNonIntegerPrice(String body) throws Exception {
    mvc.perform(asSeller(post(PRODUCTS)).header("Idempotency-Key", KEY).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value("입력값을 확인해 주세요."));
    verifyNoInteractions(catalog);
  }

  @Test
  @DisplayName("형식이 잘못된 상품 ID는 400이다")
  void rejectsMalformedProductId() throws Exception {
    mvc.perform(asSeller(get(PRODUCTS + "/not-a-uuid")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("productId"));
  }

  @Test
  @DisplayName("없거나 타인 소유인 상품은 404 공통 오류이다")
  void mapsMissingProductToNotFound() throws Exception {
    given(catalog.sellerProduct(SELLER, PRODUCT)).willThrow(new ProductNotFoundException(PRODUCT));

    mvc.perform(asSeller(get(PRODUCTS + "/" + PRODUCT)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.type").value("urn:commerce-settle:error:common-resource-not-found"));
  }

  @Test
  @DisplayName("기대 버전이 다르면 409 버전 충돌이다")
  void mapsVersionConflict() throws Exception {
    given(
            catalog.change(
                eq(SELLER), eq(PRODUCT), anyString(), anyString(), anyLong(), any(), anyLong()))
        .willThrow(new VersionConflictException("product", 0, 1));

    mvc.perform(
            asSeller(put(PRODUCTS + "/" + PRODUCT))
                .header("Idempotency-Key", KEY)
                .content(
                    "{\"name\":\"키보드\",\"unitPrice\":1000,\"status\":\"STOPPED\","
                        + "\"expectedVersion\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("같은 키의 다른 본문은 409 멱등 충돌이다")
  void mapsIdempotencyConflict() throws Exception {
    given(catalog.register(any(), anyString(), anyString(), anyLong(), any()))
        .willThrow(new IdempotencyConflictException("conflict"));

    mvc.perform(asSeller(post(PRODUCTS)).header("Idempotency-Key", KEY).content(VALID_PRODUCT))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));
  }

  @Test
  @DisplayName("논리삭제는 expectedVersion 쿼리가 필요하고 성공하면 204이다")
  void deletesWithExpectedVersion() throws Exception {
    mvc.perform(asSeller(delete(PRODUCTS + "/" + PRODUCT)).header("Idempotency-Key", KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("expectedVersion"));

    mvc.perform(
            asSeller(delete(PRODUCTS + "/" + PRODUCT))
                .header("Idempotency-Key", KEY)
                .param("expectedVersion", "3"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("재고 조정 증감이 0이면 delta 필드 오류로 400이다")
  void rejectsZeroInventoryDelta() throws Exception {
    mvc.perform(
            asSeller(post(PRODUCTS + "/" + PRODUCT + "/inventory-adjustments"))
                .header("Idempotency-Key", KEY)
                .content("{\"delta\":0,\"expectedVersion\":0,\"reason\":\"입고\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("delta"));
    verifyNoInteractions(catalog);
  }

  @Test
  @DisplayName("가용 재고보다 많이 줄이면 409 재고 부족이다")
  void mapsInsufficientInventory() throws Exception {
    given(catalog.adjustAvailable(SELLER, PRODUCT, KEY, -5, 0, "폐기"))
        .willThrow(new InsufficientInventoryException(PRODUCT));

    mvc.perform(
            asSeller(post(PRODUCTS + "/" + PRODUCT + "/inventory-adjustments"))
                .header("Idempotency-Key", KEY)
                .content("{\"delta\":-5,\"expectedVersion\":0,\"reason\":\"폐기\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT"));
  }

  @Test
  @DisplayName("목록 size가 100을 넘으면 400이다")
  void rejectsOversizedPage() throws Exception {
    mvc.perform(asSeller(get(PRODUCTS)).param("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("size"));
  }

  private static MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder request) {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .header(AuthenticatedSellerResolver.ACTOR_KIND, "SELLER")
        .header(AuthenticatedSellerResolver.ACTOR_ID, SELLER.toString());
  }

  private static Product product(ProductStatus status) {
    return Product.register(
        PRODUCT, SELLER, "키보드", 39_000, status, Instant.parse("2026-10-28T01:00:00Z"));
  }
}
