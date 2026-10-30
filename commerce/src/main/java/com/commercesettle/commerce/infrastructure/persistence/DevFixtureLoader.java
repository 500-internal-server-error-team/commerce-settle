package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.application.service.InventoryReservationService;
import com.commercesettle.commerce.domain.model.OrderAmounts;
import com.commercesettle.commerce.domain.model.OrderDelivery;
import com.commercesettle.commerce.domain.model.SealedShippingAddress;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.annotation.Transactional;

/** 개발용 고정 ID만 추가한다. 논리삭제 행도 존재하는 데이터로 보며, 기존 값은 갱신하지 않는다. */
public class DevFixtureLoader {
  private final JdbcOperations jdbc;
  private final PasswordHasher passwords;
  private final AddressCipher addresses;
  private final InventoryReservationService reservations;

  public DevFixtureLoader(
      JdbcOperations jdbc,
      PasswordHasher passwords,
      AddressCipher addresses,
      InventoryReservationService reservations) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.addresses = addresses;
    this.reservations = reservations;
  }

  /** 한 번의 적재를 원자적으로 확정한다. 충돌이나 암호화 실패 시 이번에 추가한 행을 모두 롤백한다. */
  @Transactional
  public void load(String password) {
    TransactionLimits.apply(jdbc);
    String hash = passwords.hash(password);
    buyer(1, "fixture-buyer-a@example.test", "실험 구매자 A", hash);
    buyer(11, "fixture-buyer-b@example.test", "실험 구매자 B", hash);
    seller(2, "fixture-seller-a@example.test", "실험 판매자 A", hash);
    seller(22, "fixture-seller-b@example.test", "실험 판매자 B", hash);
    product(3, 2, "수납 상자", 23_000);
    product(23, 22, "키보드", 39_000);
    product(33, 2, "머그컵", 10_000);
    address(1, 101);
    address(11, 111);
    for (int suffix : new int[] {41, 51, 61, 71, 81, 91}) {
      buyer(
          suffix,
          suffix == 91
              ? "fixture-shared@example.test"
              : "fixture-buyer-" + suffix + "@example.test",
          "실험 구매자 " + suffix,
          hash);
    }
    for (int suffix : new int[] {72, 82, 92}) {
      seller(
          suffix,
          suffix == 92
              ? "fixture-shared@example.test"
              : "fixture-seller-" + suffix + "@example.test",
          "실험 판매자 " + suffix,
          hash);
    }
    // 새 계정만 상태를 설정한다. 재적재 시 사용자가 바꾼 상태는 보존한다.
    for (int i = 0; i < 21; i++) {
      product(400 + i, 2, "페이지 실험 상품 " + (i + 1), 10_000);
    }
    product(430, 2, "품절 상품", 10_000, "ON_SALE", 0);
    product(431, 2, "초안 상품", 10_000, "DRAFT", 100);
    product(432, 2, "판매 중지 상품", 10_000, "STOPPED", 100);
    product(433, 2, "삭제 상품", 10_000, "DELETED", 100);
    for (int i = 0; i < 22; i++) address(1, 120 + i, false, false);
    address(51, 151);
    address(51, 152, false, false);
    address(51, 153, false, true);
    address(61, 161, false, false);
    address(61, 162, false, false);
    for (int i = 0; i < 5; i++) inventoryScenario(i);
    for (int i = 0; i < 4; i++) orderSnapshot(i);
  }

  private void buyer(int suffix, String email, String name, String hash) {
    jdbc.update(
        """
        INSERT INTO commerce.buyer_accounts
            (id,email_normalized,password_hash,display_name,status,auth_version,
             created_at,updated_at,version,deleted_at)
        VALUES (?,?,?,?,?,0,now(),now(),0,?) ON CONFLICT (id) DO NOTHING
        """,
        id(suffix),
        email,
        hash,
        name,
        suffix == 71 ? "SUSPENDED" : suffix == 81 ? "DELETED" : "ACTIVE",
        suffix == 81 ? java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")) : null);
    verifyEmail("commerce.buyer_accounts", suffix, email);
  }

  private void seller(int suffix, String email, String name, String hash) {
    jdbc.update(
        """
        INSERT INTO commerce.seller_accounts
            (id,email_normalized,password_hash,display_name,status,auth_version,
             payout_account_ref,created_at,updated_at,version,deleted_at)
        VALUES (?,?,?,?,?,0,?,now(),now(),0,?) ON CONFLICT (id) DO NOTHING
        """,
        id(suffix),
        email,
        hash,
        name,
        suffix == 72 ? "SUSPENDED" : suffix == 82 ? "DELETED" : "ACTIVE",
        "mock-seller-" + id(suffix),
        suffix == 82 ? java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")) : null);
    verifyEmail("commerce.seller_accounts", suffix, email);
  }

  // 테이블 이름은 위 두 고정 호출만 사용한다. 기존 ID가 다른 계정이면 그 계정을 fixture로 취급하지 않는다.
  private void verifyEmail(String table, int suffix, String email) {
    String existing =
        jdbc.queryForObject(
            "SELECT email_normalized FROM " + table + " WHERE id=?", String.class, id(suffix));
    if (!email.equals(existing)) {
      throw new IllegalStateException("Dev fixture account ID conflicts with existing data");
    }
  }

  private void product(int suffix, int seller, String name, long price) {
    product(suffix, seller, name, price, "ON_SALE", 100);
  }

  private void product(int suffix, int seller, String name, long price, String status, int stock) {
    jdbc.update(
        """
        INSERT INTO commerce.products
            (id,seller_id,name,unit_price,status,created_at,updated_at,version,deleted_at)
        VALUES (?,?,?,?,?,now(),now(),0,?) ON CONFLICT (id) DO NOTHING
        """,
        id(suffix),
        id(seller),
        name,
        price,
        status,
        "DELETED".equals(status)
            ? java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"))
            : null);
    UUID owner =
        jdbc.queryForObject(
            "SELECT seller_id FROM commerce.products WHERE id=?", UUID.class, id(suffix));
    if (!id(seller).equals(owner)) {
      throw new IllegalStateException("Dev fixture product ID conflicts with existing data");
    }
    int inserted =
        jdbc.update(
            """
        INSERT INTO commerce.product_inventory
            (id,product_id,available_qty,reserved_qty,event_allocated_qty,sold_qty,
             created_at,updated_at,version)
        VALUES (?,?,?,0,0,0,now(),now(),0) ON CONFLICT (product_id) DO NOTHING
        """,
            id(1_000 + suffix),
            id(suffix),
            stock);
    if (inserted == 1 && stock > 0) {
      jdbc.update(
          """
          INSERT INTO commerce.inventory_movements
              (id,product_id,operation,business_ref,available_delta,reserved_delta,
               allocated_delta,sold_delta,reason,created_at)
          VALUES (?,?,'SELLER_ADJUSTMENT',?,?,0,0,0,'개발 fixture 초기 재고',now())
          """,
          id(2_000 + suffix),
          id(suffix),
          id(2_000 + suffix),
          stock);
    }
  }

  private void address(int buyer, int suffix) {
    address(buyer, suffix, true, false);
  }

  private void address(int buyer, int suffix, boolean defaultCandidate, boolean deleted) {
    // 주소록 API와 같은 구매자 행을 잠가 첫 주소·기본 주소 경합을 직렬화한다.
    if (jdbc.queryForList(
            "SELECT id FROM commerce.buyer_accounts WHERE id=? AND status='ACTIVE' FOR UPDATE",
            UUID.class,
            id(buyer))
        .isEmpty()) {
      return;
    }
    if (!jdbc.queryForList(
            "SELECT id FROM commerce.buyer_addresses WHERE id=?", UUID.class, id(suffix))
        .isEmpty()) {
      return;
    }
    SealedShippingAddress sealed =
        addresses.seal(
            id(suffix),
            new ShippingAddress(
                "실험 수령인 " + buyer, "000-0000-0000", "00001", "가상시 테스트구 샘플로 1", "101호"));
    jdbc.update(
        """
        INSERT INTO commerce.buyer_addresses
            (id,buyer_id,recipient_ciphertext,phone_ciphertext,address_ciphertext,
             is_default,created_at,updated_at,version,deleted_at)
        VALUES (?,?,?,?,?,? AND NOT EXISTS
            (SELECT 1 FROM commerce.buyer_addresses WHERE buyer_id=? AND deleted_at IS NULL),
            now(),now(),0,?)
        """,
        id(suffix),
        id(buyer),
        sealed.recipient(),
        sealed.phone(),
        sealed.address(),
        defaultCandidate && !deleted,
        id(buyer),
        deleted ? java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")) : null);
  }

  private void inventoryScenario(int index) {
    UUID order =
        UUID.nameUUIDFromBytes(
            ("dev-fixture-v1:inventory-order-" + index).getBytes(StandardCharsets.UTF_8));
    if (!jdbc.queryForList(
            "SELECT id FROM commerce.inventory_reservations WHERE order_id=?", UUID.class, order)
        .isEmpty()) return;
    UUID cycle =
        UUID.nameUUIDFromBytes(
            ("dev-fixture-v1:inventory-cycle-" + index).getBytes(StandardCharsets.UTF_8));
    reservations.reserve(order, id(400 + index), 2, cycle, Instant.parse("2026-09-02T00:00:00Z"));
    if (index == 1 || index >= 3) reservations.consume(order, cycle);
    if (index == 2) reservations.release(order, cycle);
    if (index == 3) reservations.restoreForRefund(order, order);
    if (index == 4) reservations.restoreForReturn(order, order);
  }

  /** 주문 저장 모델의 요청 사본이다. 실제 주문 생성·Outbox 실행 결과와 구분한다. */
  private void orderSnapshot(int index) {
    UUID order = fixtureId("request-order-" + index);
    UUID cycle = fixtureId("request-cycle-" + index);
    int buyer = index == 0 ? 11 : index == 3 ? 41 : 1;
    int product = index == 0 ? 23 : index == 1 ? 3 : 33;
    int seller = index == 0 ? 22 : 2;
    long gross = index == 0 ? 39_000 : index == 1 ? 23_000 : 10_000;
    long mileage = index == 0 ? 0 : index == 1 ? 1_000 : 10_000;
    OrderAmounts amounts = new OrderAmounts(gross, 0, 0, mileage);
    OrderDelivery delivery =
        OrderDelivery.capture(order, 20261030, "fixture-calendar-v1", List.of());
    var at = java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"));
    jdbc.update(
        """
        INSERT INTO commerce.orders
          (id,buyer_id,seller_id,product_id,product_name,unit_price,quantity,gross_amount,
           coupon_amount,seller_coupon_amount,platform_coupon_amount,settlement_base_amount,
           total_amount,mileage_amount,external_amount,payment_cycle_id,external_method,
           method_account_ref,status,fulfillment_status,simulation_seed,delivery_business_days,
           delivery_calendar_version,delivery_calendar,payment_wait_until,policy_version,
           created_at,updated_at,version)
        VALUES (?,?,?,?,?,?,1,?,0,0,0,?,?,?,?,?,?,?,'PAYMENT_PENDING','UNSHIPPED',20261030,?,
          'fixture-calendar-v1','{"timeZone":"Asia/Seoul","holidays":[]}'::jsonb,?,'fixture-v1',?,?,0)
        ON CONFLICT (id) DO NOTHING
        """,
        order,
        id(buyer),
        id(seller),
        id(product),
        index == 0 ? "키보드" : index == 1 ? "수납 상자" : "머그컵",
        gross,
        gross,
        amounts.settlementBaseAmount(),
        amounts.totalAmount(),
        mileage,
        amounts.externalAmount(),
        cycle,
        mileage == gross ? null : "CREDIT_CARD",
        mileage == gross ? null : "fixture-card",
        delivery.getDeliveryBusinessDays(),
        java.sql.Timestamp.from(at.toInstant().plusSeconds(600)),
        at,
        at);
    var existing =
        jdbc.queryForMap(
            "SELECT buyer_id,seller_id,product_id,payment_cycle_id FROM commerce.orders WHERE id=?",
            order);
    if (!id(buyer).equals(existing.get("buyer_id"))
        || !id(seller).equals(existing.get("seller_id"))
        || !id(product).equals(existing.get("product_id"))
        || !cycle.equals(existing.get("payment_cycle_id"))) {
      throw new IllegalStateException("Dev fixture order ID conflicts with existing data");
    }
    UUID address = fixtureId("request-address-" + index);
    if (!jdbc.queryForList(
            "SELECT id FROM commerce.order_addresses WHERE order_id=?", UUID.class, order)
        .isEmpty()) {
      UUID existingAddress =
          jdbc.queryForObject(
              "SELECT id FROM commerce.order_addresses WHERE order_id=?", UUID.class, order);
      if (!address.equals(existingAddress))
        throw new IllegalStateException("Dev fixture order address conflicts");
      return;
    }
    SealedShippingAddress sealed =
        addresses.seal(
            address,
            new ShippingAddress(
                "실험 주문 수령인 " + buyer, "000-0000-0000", "00001", "가상시 테스트구 샘플로 1", "101호"));
    jdbc.update(
        """
        INSERT INTO commerce.order_addresses
          (id,order_id,recipient_ciphertext,phone_ciphertext,address_ciphertext,created_at,updated_at,version)
        VALUES (?,?,?,?,?,?,?,0)
        """,
        address,
        order,
        sealed.recipient(),
        sealed.phone(),
        sealed.address(),
        at,
        at);
  }

  private static UUID fixtureId(String name) {
    return UUID.nameUUIDFromBytes(("dev-fixture-v1:" + name).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
