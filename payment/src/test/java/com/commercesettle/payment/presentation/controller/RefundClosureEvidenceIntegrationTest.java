package com.commercesettle.payment.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.internalauth.InternalAuthAutoConfiguration;
import com.commercesettle.payment.infrastructure.persistence.JdbcRefundClosureQuery;
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
      "app.internal-auth.service=payment",
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
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

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
        "INSERT INTO payment.payments (id, order_id, buyer_id, seller_id, order_version, product_id, current_cycle_id, gross_amount, coupon_amount, seller_coupon_amount, platform_coupon_amount, settlement_base_amount, total_amount, mileage_amount, external_amount, fee_amount, seller_net_amount, status, policy_version, created_at, updated_at, version) VALUES (?,?,?,gen_random_uuid(),0,gen_random_uuid(),gen_random_uuid(),10000,0,0,0,10000,10000,2000,8000,1500,8500,'CONFIRMED','test',?,?,0)",
        payment,
        order,
        buyer,
        AT,
        AT);
    jdbc.update(
        "INSERT INTO payment.refunds (id,payment_id,order_refund_request_id,external_amount,mileage_amount,status,business_key,external_status,mileage_restored_at,created_at,updated_at,version) VALUES (?,?,?,8000,2000,'DECLINED',gen_random_uuid(),'DECLINED',NULL,?,?,3)",
        refund,
        payment,
        request,
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
        .andExpect(jsonPath("$.buyerId").value(buyer.toString()))
        .andExpect(jsonPath("$.requestId").value(request.toString()))
        .andExpect(jsonPath("$.mileageNotRestored").value(true))
        .andExpect(jsonPath("$.decidedAt").value(AT.toString()));
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE)
                .header("X-Authenticated-Actor-Id", buyer))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/refunds/" + UUID.randomUUID() + "/financial-closure-evidence")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("마일리지 복원 원장이 있으면 미복원으로 증언하지 않는다")
  void detectsRestorationInOwnerLedger() throws Exception {
    jdbc.update(
        "INSERT INTO payment.mileage_entries (id,wallet_id,payment_id,refund_id,kind,business_ref,amount,balance_after,created_at) VALUES (gen_random_uuid(),gen_random_uuid(),?,?,'RESTORE',gen_random_uuid(),2000,2000,?)",
        payment,
        refund,
        AT);
    mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mileageNotRestored").value(false));
  }

  @Test
  @DisplayName("선행 금융 스키마가 없으면 503으로 닫는다")
  void rejectsMissingFinancialSchema() throws Exception {
    jdbc.execute("ALTER TABLE payment.refunds RENAME TO unavailable_refunds");
    try {
      mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
          .andExpect(status().isServiceUnavailable());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from payment.payments where id=?", Long.class, payment))
          .isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE payment.unavailable_refunds RENAME TO refunds");
    }
    mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + COMMERCE))
        .andExpect(status().isOk());
  }

  private String path() {
    return "/internal/refunds/" + refund + "/financial-closure-evidence";
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
