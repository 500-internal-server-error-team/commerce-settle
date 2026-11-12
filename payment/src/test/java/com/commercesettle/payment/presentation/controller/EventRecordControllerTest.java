package com.commercesettle.payment.presentation.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.internalauth.InternalAuthAutoConfiguration;
import com.commercesettle.payment.application.port.EventRecordReader.Entry;
import com.commercesettle.payment.application.service.EventRecordQueryService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = EventRecordController.class,
    properties = {
      "app.internal-auth.service=payment",
      "app.internal-auth.senders.gateway.current=" + EventRecordControllerTest.GATEWAY,
      "app.internal-auth.senders[operator\\:ops-1].current=" + EventRecordControllerTest.OPERATOR
    })
@Import(InternalAuthAutoConfiguration.class)
class EventRecordControllerTest {
  static final String GATEWAY = "gateway-credential-0123456789abcdef0123456789";
  static final String OPERATOR = "operator-credential-0123456789abcdef01234567";
  static final String PATH = "/internal/payment-event-snapshot";
  static final UUID ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
  @Autowired MockMvc mvc;
  @MockitoBean EventRecordQueryService service;

  @Test
  @DisplayName("운영자만쉼표UUID목록으로관측하고KST·int64·null위치를보존한다")
  void returnsOperatorSnapshot() throws Exception {
    Instant at = Instant.parse("2026-11-05T03:00:00Z");
    var row =
        new Entry(
            "INBOX",
            ID,
            ID,
            "PaymentConfirmed",
            ID,
            "WAITING",
            "commerce-settle.payment.v1",
            "payment.v1",
            1,
            9007199254740993L,
            2,
            null,
            null,
            at,
            at,
            List.of(ID));
    given(service.get(anyList()))
        .willReturn(new EventRecordQueryService.Snapshot(List.of(row), 50, false, at));
    mvc.perform(
            get(PATH)
                .header("Authorization", "Bearer " + OPERATOR)
                .param("ids", ID + "," + UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.records[0].eventId").value(ID.toString()))
        .andExpect(jsonPath("$.records[0].offset").value(9007199254740993L))
        .andExpect(jsonPath("$.records[0].payload").doesNotExist())
        .andExpect(jsonPath("$.records[0].rawBody").doesNotExist())
        .andExpect(jsonPath("$.observedAt").value("2026-11-05T12:00:00+09:00"));
  }

  @Test
  @DisplayName("미인증과Gateway자격은DB조회전에거절한다")
  void refusesNonOperatorBeforeQuery() throws Exception {
    mvc.perform(get(PATH).param("ids", ID.toString())).andExpect(status().isUnauthorized());
    mvc.perform(get(PATH).header("Authorization", "Bearer " + GATEWAY).param("ids", ID.toString()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-uuid", "aaaaaaaa-0000-4000-8000-000000000001,"})
  @DisplayName("빈값과잘못된UUID를조회전에거절한다")
  void refusesInvalidReferences(String ids) throws Exception {
    mvc.perform(get(PATH).header("Authorization", "Bearer " + OPERATOR).param("ids", ids))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("필수ID누락과20개초과를조회전에거절한다")
  void refusesMissingOrOversizedReferences() throws Exception {
    mvc.perform(get(PATH).header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(PATH)
                .header("Authorization", "Bearer " + OPERATOR)
                .param("ids", String.join(",", Collections.nCopies(21, ID.toString()))))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
