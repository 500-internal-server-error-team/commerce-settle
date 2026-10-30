package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.Status;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/** 금융 스냅샷과 실제 Inbox 처리 시나리오를 구분한다. 다른 서비스 DB는 읽지 않는다. */
@Component
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureLoader {
  public static final String TOPIC = "commerce-settle.fixture.payment.v1";
  private static final Instant AS_OF = Instant.parse("2026-09-01T00:00:00Z");
  private final JdbcTemplate jdbc;
  private final TransactionOperations transactions;
  private final MileageWalletRepository wallets;
  private final MileageEntryRepository entries;
  private final OrderPaymentRequestAcceptor acceptor;
  private final ApplyOrderPaymentRequestService processor;
  private final KafkaTemplate<String, byte[]> kafka;
  private final ObjectMapper mapper;
  private final String consumer;

  public DevFixtureLoader(
      JdbcTemplate jdbc,
      TransactionOperations transactions,
      MileageWalletRepository wallets,
      MileageEntryRepository entries,
      OrderPaymentRequestAcceptor acceptor,
      ApplyOrderPaymentRequestService processor,
      KafkaTemplate<String, byte[]> kafka,
      ObjectMapper mapper,
      @Value("${spring.kafka.consumer.group-id:payment.v1}") String consumer) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.wallets = wallets;
    this.entries = entries;
    this.acceptor = acceptor;
    this.processor = processor;
    this.kafka = kafka;
    this.mapper = mapper;
    this.consumer = consumer;
  }

  public void load() {
    transactions.executeWithoutResult(
        tx -> {
          // 동일 서비스의 중복 API 기동을 직렬화한다. DB 세션·다른 서비스의 잠금은 점유하지 않는다.
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          jdbc.queryForList("SELECT pg_advisory_xact_lock(900901)");
          wallet(1, 100_000);
          wallet(11, 0);
          wallet(41, 0);
          wallet(61, 500);
          for (String state :
              new String[] {
                "PENDING", "PROCESSING", "CONFIRMED", "DECLINED", "UNKNOWN", "CANCELLED"
              }) {
            snapshot("state-" + state, 2, 3, 23_000, state);
          }
          for (int i = 0; i < 8; i++) {
            boolean secondSeller = i == 4 || i == 5;
            snapshot(
                "settlement-" + i,
                secondSeller ? 22 : 2,
                secondSeller ? 23 : 3,
                secondSeller ? 39_000 : 23_000,
                "CONFIRMED");
          }
        });
  }

  private void wallet(int buyer, long balance) {
    if (wallets.findByBuyerIdForUpdate(account(buyer)).isPresent()) return;
    MileageWallet wallet = wallets.save(MileageWallet.open(account(buyer), balance, AS_OF));
    if (balance > 0) entries.save(MileageEntry.seed(wallet, AS_OF));
  }

  private void snapshot(String name, int seller, int product, long gross, String state) {
    PaymentAmounts amounts = new PaymentAmounts(gross, 0, 0, 0);
    jdbc.update(
        """
        INSERT INTO payment.payments
          (id,order_id,buyer_id,seller_id,order_version,product_id,current_cycle_id,
           gross_amount,coupon_amount,seller_coupon_amount,platform_coupon_amount,
           settlement_base_amount,total_amount,mileage_amount,external_amount,fee_amount,
           seller_net_amount,status,confirmed_at,policy_version,created_at,updated_at,version)
        VALUES (?,?,?,?,0,?,?,?,0,0,0,?,?,0,?,?,?,?,?,'fixture-v1',?,?,0)
        ON CONFLICT (order_id) DO NOTHING
        """,
        id("payment-" + name),
        id("order-" + name),
        account(1),
        account(seller),
        account(product),
        id("cycle-" + name),
        gross,
        amounts.settlementBaseAmount(),
        amounts.totalAmount(),
        amounts.externalAmount(),
        amounts.feeAmount(),
        amounts.sellerNetAmount(),
        state,
        "CONFIRMED".equals(state) ? java.sql.Timestamp.from(AS_OF) : null,
        java.sql.Timestamp.from(AS_OF),
        java.sql.Timestamp.from(AS_OF));
    UUID existing =
        jdbc.queryForObject(
            "SELECT id FROM payment.payments WHERE order_id=?", UUID.class, id("order-" + name));
    if (!id("payment-" + name).equals(existing))
      throw new IllegalStateException("Fixture payment order conflicts");
  }

  /** Kafka에 실제 발행한 위치로 접수한다. 중간 실패 후 같은 eventId·본문으로 재개한다. */
  public void runScenarios() throws Exception {
    for (int index = 0; index < 4; index++) {
      var event = event(index);
      boolean exists =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM payment.inbox WHERE consumer=? AND event_id=?)",
                  Boolean.class,
                  consumer,
                  event.eventId()));
      if (!exists) {
        byte[] body = mapper.writeValueAsBytes(event);
        var metadata =
            kafka
                .send(TOPIC, event.aggregateId().toString(), body)
                .get(15, TimeUnit.SECONDS)
                .getRecordMetadata();
        acceptor.accept(
            event, new Source(metadata.topic(), metadata.partition(), metadata.offset(), body));
      }
      processor.processEvent(event.eventId());
    }
  }

  public static EventEnvelope<OrderPaymentRequestedPayload> event(int index) {
    if (index < 0 || index > 3) throw new IllegalArgumentException("Unknown fixture scenario");
    int buyer = index == 0 ? 11 : index == 3 ? 41 : 1;
    int product = index == 0 ? 23 : index == 1 ? 3 : 33;
    long gross = index == 0 ? 39_000 : index == 1 ? 23_000 : 10_000;
    long mileage = index == 0 ? 0 : index == 1 ? 1_000 : 10_000;
    var at = OffsetDateTime.parse("2026-09-01T09:00:00+09:00");
    UUID order = id("request-order-" + index);
    return new EventEnvelope<>(
        id("request-event-" + index),
        "OrderPaymentRequested",
        1,
        at,
        order,
        0,
        new OrderPaymentRequestedPayload(
            order,
            account(buyer),
            account(index == 0 ? 22 : 2),
            id("request-cycle-" + index),
            account(product),
            0,
            Status.PAYMENT_PENDING,
            "fixture-v1",
            at.plusMinutes(10),
            mileage == gross ? null : ExternalMethod.CREDIT_CARD,
            mileage == gross ? null : "fixture-card",
            gross,
            0,
            gross,
            mileage,
            gross - mileage,
            0,
            0,
            gross));
  }

  public static UUID id(String name) {
    return UUID.nameUUIDFromBytes(("dev-fixture-v1:" + name).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID account(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
