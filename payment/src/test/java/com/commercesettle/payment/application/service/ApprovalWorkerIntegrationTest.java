package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 PostgreSQL에서 승인 작업 임대·외부 호출 전후 트랜잭션·결과 반영을 확인한다. PG 대역은 결과를 정할 수 있는 대역으로 바꾼다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ApprovalWorkerIntegrationTest.Config.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalWorkerIntegrationTest {
  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
  private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService requests;
  @Autowired private ApprovalWorker worker;
  @Autowired private FakeGateway gateway;
  @Autowired private InboxStore inbox;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  @AfterEach
  void deleteRows() {
    gateway.reset();
    for (String table :
        List.of(
            "mileage_entries",
            "mileage_reservations",
            "mileage_wallets",
            "payment_attempts",
            "payment_jobs",
            "payments",
            "inbox",
            "outbox")) {
      jdbc().update("DELETE FROM payment." + table);
    }
  }

  @Test
  @DisplayName("카드 결제를 승인하면 시도·결제 확정·작업 종료·PaymentConfirmed가 함께 커밋된다")
  void confirmsCardPayment() {
    UUID orderId = request(10_000, 0, "CREDIT_CARD");

    assertThat(worker.runOnce()).isTrue();

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT provider_id, method, account_ref, amount, status, external_ref IS NOT"
                        + " NULL AS has_ref FROM payment.payment_attempts"))
        .containsEntry("provider_id", "PG_A")
        .containsEntry("method", "CREDIT_CARD")
        .containsEntry("account_ref", "card-ref")
        .containsEntry("amount", 10_000L)
        .containsEntry("status", "APPROVED")
        .containsEntry("has_ref", true);
    assertThat(jobStatus()).isEqualTo("DONE");
    assertThat(outbox()).containsExactly("PaymentAccepted:0", "PaymentConfirmed:2");
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT payload -> 'payload' ->> 'orderId' FROM payment.outbox"
                        + " WHERE event_type = 'PaymentConfirmed'",
                    String.class))
        .isEqualTo(orderId.toString());
    assertThat(gateway.approvals).hasSize(1);
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  @DisplayName("혼합 결제를 승인하면 예약한 마일리지를 차감하고, 간편결제는 EASY_PAY 대역으로 보낸다")
  void consumesMileageOnApproval() {
    fundWallet(5_000);
    request(10_000, 2_000, "EASY_PAY");

    worker.runOnce();

    assertThat(gateway.approvals.get(0).providerId()).isEqualTo("EASY_PAY");
    assertThat(gateway.approvals.get(0).amount()).isEqualTo(8_000);
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT balance, reserved_amount FROM payment.mileage_wallets WHERE buyer_id ="
                        + " ?",
                    BUYER_ID))
        .containsEntry("balance", 3_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT kind FROM payment.mileage_entries ORDER BY kind", String.class))
        .containsExactly("DEBIT", "RESERVE");
  }

  @Test
  @DisplayName("확정 거절되면 결제를 거절하고 마일리지 예약을 풀고 PaymentDeclined를 남긴다")
  void declinesPaymentAndReleasesMileage() {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve = r -> new Result.Declined(UUID.randomUUID());

    worker.runOnce();

    assertThat(status()).isEqualTo("DECLINED");
    assertThat(
            jdbc().queryForObject("SELECT status FROM payment.mileage_reservations", String.class))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT reserved_amount FROM payment.mileage_wallets WHERE buyer_id = ?",
                    Long.class,
                    BUYER_ID))
        .isZero();
    assertThat(outbox()).containsExactly("PaymentAccepted:0", "PaymentDeclined:2");
  }

  @Test
  @DisplayName("결과를 모르면 미확정으로 두고 다시 실행할 때 승인을 반복하지 않고 원거래를 조회해 확정한다")
  void looksUpUnconfirmedApprovalInsteadOfRetrying() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve = r -> new Result.Unconfirmed("timeout");

    worker.runOnce();

    assertThat(status()).isEqualTo("UNKNOWN");
    assertThat(jobStatus()).isEqualTo("RETRY");
    makeJobDue();
    gateway.lookup = r -> new Result.Succeeded(UUID.randomUUID(), NOW);

    worker.runOnce();

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(gateway.lookups).hasSize(1);
    assertThat(outbox()).containsExactly("PaymentAccepted:0", "PaymentConfirmed:3");
  }

  @Test
  @DisplayName("호출 전 중단 뒤 임대가 끝나면 원거래를 조회하고, 거래가 없으면 같은 업무 키·본문으로 재전송한다")
  void resendsSameRequestWhenTransactionWasNotFound() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve =
        r -> {
          throw new IllegalStateException("worker stopped before the call reached the provider");
        };
    assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException.class);
    assertThat(status()).isEqualTo("PROCESSING");
    expireLease();
    gateway.approve = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
    gateway.lookup = r -> new Result.NotFound();

    worker.runOnce();

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(2);
    assertThat(gateway.approvals.get(1)).isEqualTo(gateway.approvals.get(0));
    assertThat(outbox()).containsExactly("PaymentAccepted:0", "PaymentConfirmed:2");
  }

  @Test
  @DisplayName("응답을 받는 사이 다른 실행자가 임대를 넘겨받았으면 늦은 결과로 결제를 바꾸지 않는다")
  void ignoresLateResultAfterLeaseWasTakenOver() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve =
        r -> {
          jdbc().update("UPDATE payment.payment_jobs SET lease_token = ?", UUID.randomUUID());
          return new Result.Succeeded(UUID.randomUUID(), NOW);
        };

    worker.runOnce();

    assertThat(status()).isEqualTo("PROCESSING");
    assertThat(jdbc().queryForObject("SELECT status FROM payment.payment_attempts", String.class))
        .isEqualTo("PROCESSING");
    assertThat(outbox()).containsExactly("PaymentAccepted:0");
  }

  @Test
  @DisplayName("외부 호출 전에 취소된 결제는 대역을 호출하지 않고 작업을 끝낸다")
  void skipsCancelledPayment() {
    request(10_000, 0, "CREDIT_CARD");
    jdbc().update("UPDATE payment.payments SET status = 'CANCELLED', version = version + 1");

    worker.runOnce();

    assertThat(gateway.approvals).isEmpty();
    assertThat(jobStatus()).isEqualTo("DONE");
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_attempts", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("시도 상한을 넘겨 다시 회수된 작업은 호출하지 않고 확인 대상으로 넘긴다")
  void requiresReviewAfterExhaustingAttempts() {
    request(10_000, 0, "CREDIT_CARD");
    jdbc().update("UPDATE payment.payment_jobs SET attempts = 10");

    worker.runOnce();

    assertThat(gateway.approvals).isEmpty();
    assertThat(jdbc().queryForMap("SELECT status, last_error_code FROM payment.payment_jobs"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", "PAYMENT_APPROVAL_ATTEMPTS_EXHAUSTED");
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 작업 없이 결제 요청 처리에서 PaymentConfirmed를 남긴다")
  void confirmsMileageOnlyPaymentAtRequest() {
    fundWallet(12_000);

    request(10_000, 10_000, null);

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isZero();
    assertThat(outbox()).containsExactly("PaymentAccepted:0", "PaymentConfirmed:0");
    assertThat(worker.runOnce()).isFalse();
  }

  private String status() {
    return jdbc().queryForObject("SELECT status FROM payment.payments", String.class);
  }

  private String jobStatus() {
    return jdbc().queryForObject("SELECT status FROM payment.payment_jobs", String.class);
  }

  private List<String> outbox() {
    return jdbc()
        .queryForList(
            "SELECT event_type || ':' || aggregate_version FROM payment.outbox ORDER BY sequence_no",
            String.class);
  }

  private void makeJobDue() {
    jdbc()
        .update("UPDATE payment.payment_jobs SET next_run_at = clock_timestamp() - interval '1s'");
  }

  private void expireLease() {
    jdbc()
        .update("UPDATE payment.payment_jobs SET lease_until = clock_timestamp() - interval '1s'");
  }

  private void fundWallet(long balance) {
    jdbc()
        .update(
            "INSERT INTO payment.mileage_wallets"
                + " (id, buyer_id, balance, reserved_amount, created_at, updated_at, version)"
                + " VALUES (?, ?, ?, 0, now(), now(), 0)",
            UUID.randomUUID(),
            BUYER_ID,
            balance);
  }

  /** 쿠폰 없는 결제 요청을 Inbox에 넣고 처리해 결제·승인 작업을 만든다. */
  private UUID request(long gross, long mileage, String method) {
    UUID orderId = UUID.randomUUID();
    long external = gross - mileage;
    String json =
        """
        {
          "eventId": "%s",
          "eventType": "OrderPaymentRequested",
          "eventVersion": 1,
          "occurredAt": "2026-01-01T00:00:00Z",
          "aggregateId": "%s",
          "aggregateVersion": 0,
          "payload": {
            "orderId": "%s",
            "buyerId": "%s",
            "sellerId": "00000000-0000-0000-0000-0000000000a3",
            "paymentCycleId": "%s",
            "productId": "00000000-0000-0000-0000-0000000000a5",
            "orderVersion": 0,
            "status": "PAYMENT_PENDING",
            "policyVersion": "test-v1",
            "paymentWaitUntil": "2026-01-01T00:10:00Z",
            "externalMethod": %s,
            "methodAccountRef": %s,
            "grossAmount": %d,
            "couponAmount": 0,
            "totalAmount": %d,
            "mileageAmount": %d,
            "externalAmount": %d,
            "sellerCouponAmount": 0,
            "platformCouponAmount": 0,
            "settlementBaseAmount": %d
          }
        }
        """
            .formatted(
                UUID.randomUUID(),
                orderId,
                orderId,
                BUYER_ID,
                UUID.randomUUID(),
                method == null ? "null" : "\"" + method + "\"",
                method == null ? "null" : "\"card-ref\"",
                gross,
                gross,
                mileage,
                external,
                gross);
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(body, new TypeReference<>() {});
      new TransactionTemplate(manager)
          .executeWithoutResult(
              s ->
                  inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body)));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    assertThat(requests.processNext()).isTrue();
    // 시험 시계는 미래로 고정돼 있어 작업의 처리 시각도 미래다. 임대는 DB 시각으로 판정하므로 바로 도래하게 한다.
    makeJobDue();
    return orderId;
  }

  /** 결과를 시험마다 정하는 PG 대역. 기본은 즉시 승인이다. */
  static final class FakeGateway implements PaymentGateway {
    final List<ApprovalRequest> approvals = new ArrayList<>();
    final List<ApprovalRequest> lookups = new ArrayList<>();
    Function<ApprovalRequest, Result> approve;
    Function<ApprovalRequest, Result> lookup;

    FakeGateway() {
      reset();
    }

    void reset() {
      approvals.clear();
      lookups.clear();
      approve = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
      lookup = r -> new Result.NotFound();
    }

    @Override
    public Result approve(ApprovalRequest request) {
      approvals.add(request);
      return approve.apply(request);
    }

    @Override
    public Result lookup(ApprovalRequest request) {
      lookups.add(request);
      return lookup.apply(request);
    }
  }

  @TestConfiguration
  // 같은 패키지의 다른 테스트 설정을 읽지 않도록 저장소 구현만 등록한다.
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(Repository.class))
  @Import({
    ApplyOrderPaymentRequestService.class,
    ApprovalWorker.class,
    ApprovalResultRecorder.class
  })
  static class Config {
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    FakeGateway fakeGateway() {
      return new FakeGateway();
    }

    @Bean
    ApprovalWorkerSettings approvalWorkerSettings() {
      return new ApprovalWorkerSettings(
          Duration.ofSeconds(30),
          Duration.ofSeconds(5),
          Duration.ofSeconds(5),
          10,
          "PG_A",
          "payout-funding-default",
          "pg-normal-v1");
    }
  }
}
