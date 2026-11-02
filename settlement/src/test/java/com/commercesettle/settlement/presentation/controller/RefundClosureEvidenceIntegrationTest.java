package com.commercesettle.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.internalauth.InternalAuthAutoConfiguration;
import com.commercesettle.settlement.infrastructure.persistence.JdbcRefundClosureQuery;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@WebMvcTest(
    controllers = RefundClosureEvidenceController.class,
    properties = {
      "app.internal-auth.service=settlement",
      "app.internal-auth.senders.gateway.current=" + RefundClosureEvidenceIntegrationTest.GATEWAY,
      "app.internal-auth.senders[service\\:commerce].current="
          + RefundClosureEvidenceIntegrationTest.COMMERCE
    })
@Import({
  InternalAuthAutoConfiguration.class,
  JdbcRefundClosureQuery.class,
  RefundClosureEvidenceIntegrationTest.Beans.class
})
@Testcontainers
class RefundClosureEvidenceIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String COMMERCE = "test-commerce-credential-0123456789abcdef0123";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  private final UUID refund = UUID.randomUUID();
  private final UUID payment = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();
  private final UUID buyer = UUID.randomUUID();
  private final UUID request = UUID.randomUUID();
  private final UUID item = UUID.randomUUID();
  private final UUID reservation = UUID.randomUUID();
  private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-02T10:00:00.123456+09:00");

  @BeforeEach
  void prepare() {
    jdbc.update(
        "INSERT INTO settlement.settlement_items (id,payment_id,order_id,product_id,seller_id,total_amount,platform_coupon_amount,settlement_base_amount,external_amount,mileage_amount,fee_amount,net_amount,status,order_version,created_at,updated_at,version) VALUES (?,?,?,gen_random_uuid(),gen_random_uuid(),10000,0,10000,8000,2000,1500,8500,'WAITING_CONFIRMATION',7,?,?,4)",
        item,
        payment,
        order,
        AT,
        AT);
    jdbc.update(
        "INSERT INTO settlement.settlement_reservations (id,item_id,previous_item_status,kind,business_ref,amount,status,created_at,updated_at,version) VALUES (?,?,'WAITING_CONFIRMATION','REFUND',?,8500,'RELEASED',?,?,1)",
        reservation,
        item,
        refund,
        AT,
        AT);
  }

  @Test
  @DisplayName("소유 DB의 원환불 증빙을 서비스 권한으로만 읽는다")
  void readsAuthoritativeOwnerStateThroughAuthenticatedHttp() throws Exception {
    mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundId").value(refund.toString()))
        .andExpect(jsonPath("$.paymentId").value(payment.toString()))
        .andExpect(jsonPath("$.orderId").value(order.toString()))
        .andExpect(jsonPath("$.status").value("RELEASED"))
        .andExpect(jsonPath("$.activeReservation").value(false))
        .andExpect(jsonPath("$.itemVersion").value(4))
        .andExpect(jsonPath("$.releasedAt").value(AT.toString()));
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE)
                .header("X-Authenticated-Actor-Id", buyer))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/refunds/" + UUID.randomUUID() + "/reservation-closure-evidence")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("해제 전 원예약과 새 활성 예약은 실제 상태로 반환한다")
  void exposesUnreleasedAndNewReservationsWithoutSuccessFallback() throws Exception {
    jdbc.update(
        "UPDATE settlement.settlement_reservations SET status='ACTIVE',version=2 WHERE id=?",
        reservation);
    mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.activeReservation").value(true));
    assertThat(
            jdbc.queryForObject(
                "select status from settlement.settlement_reservations where id=?",
                String.class,
                reservation))
        .isEqualTo("ACTIVE");
  }

  private String path() {
    return "/internal/refunds/" + refund + "/reservation-closure-evidence";
  }

  @TestConfiguration
  static class Beans {
    @Bean
    JdbcTemplate jdbcTemplate() {
      return new JdbcTemplate(
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
  }
}
