package com.commercesettle.commerce.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.commerce.application.service.SaleEventService;
import com.commercesettle.commerce.application.service.SaleEventService.GaugeView;
import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException.Reason;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.model.EventAdmission;
import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({SellerEventController.class, EventController.class})
class SaleEventControllerTest {
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
  private static final Instant STARTS = Instant.parse("2026-11-03T00:00:00Z");
  private static final Instant ENDS = Instant.parse("2026-11-04T00:00:00Z");
  private static final String EVENTS = "/api/sellers/me/events";
  private static final String KEY = "key-12345678";
  private static final String VALID_EVENT =
      """
      {"name":"주말 한정 특가","productId":"00000000-0000-0000-0000-0000000000b1","eventPrice":10000,
       "quota":1000,"startsAt":"2026-11-03T09:00:00+09:00","endsAt":"2026-11-04T09:00:00+09:00"}
      """;
  private static SchemaRegistry contracts;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockitoBean SaleEventService events;

  @BeforeAll
  static void loadContracts() throws Exception {
    try (var input = SaleEventControllerTest.class.getResourceAsStream("/contracts/openapi.json")) {
      assertThat(input).as("정식 OpenAPI 테스트 리소스").isNotNull();
      String document = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      contracts =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder -> builder.schemas(Map.of("urn:commerce:canonical", document)));
    }
  }

  @Test
  @DisplayName("이벤트 등록은 201과 Location, 정식 Event 계약에 맞는 미공개 이벤트를 반환한다")
  void registersEvent() throws Exception {
    given(events.register(SELLER, KEY, PRODUCT, "주말 한정 특가", 10_000, 1000, STARTS, ENDS))
        .willReturn(event());

    String body =
        mvc.perform(asSeller(post(EVENTS)).header("Idempotency-Key", KEY).content(VALID_EVENT))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", EVENTS + "/" + EVENT))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.startsAt").value("2026-11-03T09:00:00+09:00"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    matchesContract("Event", body);
    matchesContract("EventCreate", VALID_EVENT);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "\"endsAt\":\"2026-11-04T09:00:00+09:00\"|\"endsAt\":\"2026-11-03T09:00:00+09:00\"|endsAt",
        "\"endsAt\":\"2026-11-04T09:00:00+09:00\"|\"endsAt\":\"2026-11-03T08:00:00+09:00\"|endsAt",
        "\"quota\":1000|\"quota\":0|quota",
        "\"eventPrice\":10000|\"eventPrice\":10000001|eventPrice",
        "\"name\":\"주말 한정 특가\"|\"name\":\"\"|name"
      })
  @DisplayName("기간이 역전되거나 값 범위를 벗어나면 해당 필드 오류로 400이고 서비스를 호출하지 않는다")
  void rejectsInvalidEvent(String from, String to, String field) throws Exception {
    mvc.perform(
            asSeller(post(EVENTS))
                .header("Idempotency-Key", KEY)
                .content(VALID_EVENT.replace(from, to)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value(field));
    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("멱등키가 없거나 종료 사유가 없으면 400이다")
  void requiresKeyAndReason() throws Exception {
    mvc.perform(asSeller(post(EVENTS)).content(VALID_EVENT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    mvc.perform(
            asSeller(post(EVENTS + "/" + EVENT + "/closure"))
                .header("Idempotency-Key", KEY)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("reason"));
    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("미공개 이벤트 변경은 expectedVersion을 함께 넘긴다")
  void changesDraft() throws Exception {
    given(events.change(SELLER, EVENT, KEY, PRODUCT, "주말 한정 특가", 10_000, 1000, STARTS, ENDS, 3))
        .willReturn(event());

    mvc.perform(
            asSeller(put(EVENTS + "/" + EVENT))
                .header("Idempotency-Key", KEY)
                .content(VALID_EVENT.replace("}", ",\"expectedVersion\":3}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(EVENT.toString()));
  }

  @ParameterizedTest
  @CsvSource({
    "PERIOD_OVERLAP, PRODUCT_EVENT_IN_PROGRESS",
    "NOT_DRAFT, COMMON_VERSION_CONFLICT",
    "PERIOD_ENDED, COMMON_VERSION_CONFLICT",
    "PRODUCT_NOT_ON_SALE, COMMON_VERSION_CONFLICT"
  })
  @DisplayName("공개를 반영할 수 없는 상태는 사유별 code와 함께 409이다")
  void mapsPublicationConflicts(Reason reason, String code) throws Exception {
    given(events.publish(eq(SELLER), eq(EVENT), anyString(), anyLong()))
        .willThrow(new SaleEventConflictException(reason));

    mvc.perform(publication())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(code));
  }

  @Test
  @DisplayName("가용 재고보다 많은 수량을 공개하면 409 재고 부족이고, 타인·없는 이벤트는 404이다")
  void mapsInsufficientInventoryAndMissingEvent() throws Exception {
    given(events.publish(eq(SELLER), eq(EVENT), anyString(), anyLong()))
        .willThrow(new InsufficientInventoryException(PRODUCT))
        .willThrow(new SaleEventNotFoundException(EVENT));

    mvc.perform(publication())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT"));
    mvc.perform(publication())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("조기 종료는 사유와 expectedVersion을 넘기고 종료한 이벤트를 반환한다")
  void closesEvent() throws Exception {
    SaleEvent closed = event();
    ProductInventory inventory = inventory();
    closed.publish(inventory, 0, UUID.randomUUID(), NOW);
    closed.close(inventory, 0, "재고 오류", UUID.randomUUID(), NOW);
    given(events.close(SELLER, EVENT, KEY, 1, "재고 오류")).willReturn(closed);

    String body =
        mvc.perform(
                asSeller(post(EVENTS + "/" + EVENT + "/closure"))
                    .header("Idempotency-Key", KEY)
                    .content("{\"expectedVersion\":1,\"reason\":\"재고 오류\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    matchesContract("Event", body);
  }

  @Test
  @DisplayName("공개 목록·게이지는 인증 없이 정식 EventPage·EventGauge 계약으로 응답한다")
  void servesPublicEventsAndGauge() throws Exception {
    given(events.publishedEvents(0, 20))
        .willReturn(new PageResult<>(List.of(published()), 0, 20, 1));
    given(events.gauge(EVENT))
        .willReturn(
            new GaugeView(
                EVENT,
                new SaleEvent.Gauge(1000, 700, 300, 0, 70, EventAdmission.WAITING_PAYMENTS),
                NOW));

    String page =
        mvc.perform(get("/api/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String gauge =
        mvc.perform(get("/api/events/" + EVENT + "/gauge"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.percent").value(70))
            .andExpect(jsonPath("$.admission").value("WAITING_PAYMENTS"))
            .andExpect(jsonPath("$.asOf").value("2026-11-02T10:00:00+09:00"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    matchesContract("EventPage", page);
    matchesContract("EventGauge", gauge);
  }

  @Test
  @DisplayName("미공개·없는 이벤트의 공개 조회는 404이다")
  void hidesMissingPublicEvent() throws Exception {
    given(events.publishedEvent(any())).willThrow(new SaleEventNotFoundException(EVENT));

    mvc.perform(get("/api/events/" + EVENT))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"));
  }

  private void matchesContract(String name, String json) throws Exception {
    JsonNode payload = mapper.readTree(json);
    assertThat(
            contracts
                .getSchema(SchemaLocation.of("urn:commerce:canonical#/components/schemas/" + name))
                .validate(
                    payload.toString(),
                    InputFormat.JSON,
                    context ->
                        context.executionConfig(config -> config.formatAssertionsEnabled(true))))
        .as(name)
        .isEmpty();
  }

  private static MockHttpServletRequestBuilder publication() {
    return asSeller(post(EVENTS + "/" + EVENT + "/publication"))
        .header("Idempotency-Key", KEY)
        .content("{\"expectedVersion\":0}");
  }

  private static MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder request) {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .header(AuthenticatedActorResolver.ACTOR_KIND, "SELLER")
        .header(AuthenticatedActorResolver.ACTOR_ID, SELLER.toString());
  }

  private static SaleEvent event() {
    return SaleEvent.register(EVENT, SELLER, PRODUCT, "주말 한정 특가", 10_000, 1000, STARTS, ENDS, NOW);
  }

  private static SaleEvent published() {
    SaleEvent event = event();
    event.publish(inventory(), 0, UUID.randomUUID(), NOW);
    return event;
  }

  private static ProductInventory inventory() {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);
    inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), 2000, "입고", 0, NOW);
    return inventory;
  }
}
