package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.domain.model.SealedShippingAddress;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.annotation.Transactional;

/** 개발용 고정 ID만 추가한다. 논리삭제 행도 존재하는 데이터로 보며, 기존 값은 갱신하지 않는다. */
public class DevFixtureLoader {
  private final JdbcOperations jdbc;
  private final PasswordHasher passwords;
  private final AddressCipher addresses;

  public DevFixtureLoader(JdbcOperations jdbc, PasswordHasher passwords, AddressCipher addresses) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.addresses = addresses;
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
  }

  private void buyer(int suffix, String email, String name, String hash) {
    jdbc.update(
        """
        INSERT INTO commerce.buyer_accounts
            (id,email_normalized,password_hash,display_name,status,auth_version,
             created_at,updated_at,version)
        VALUES (?,?,?,?,'ACTIVE',0,now(),now(),0) ON CONFLICT (id) DO NOTHING
        """,
        id(suffix),
        email,
        hash,
        name);
    verifyEmail("commerce.buyer_accounts", suffix, email);
  }

  private void seller(int suffix, String email, String name, String hash) {
    jdbc.update(
        """
        INSERT INTO commerce.seller_accounts
            (id,email_normalized,password_hash,display_name,status,auth_version,
             payout_account_ref,created_at,updated_at,version)
        VALUES (?,?,?,?,'ACTIVE',0,?,now(),now(),0) ON CONFLICT (id) DO NOTHING
        """,
        id(suffix),
        email,
        hash,
        name,
        "fixture-payout-" + suffix);
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
    jdbc.update(
        """
        INSERT INTO commerce.products
            (id,seller_id,name,unit_price,status,created_at,updated_at,version)
        VALUES (?,?,?,?,'ON_SALE',now(),now(),0) ON CONFLICT (id) DO NOTHING
        """,
        id(suffix),
        id(seller),
        name,
        price);
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
        VALUES (?,?,100,0,0,0,now(),now(),0) ON CONFLICT (product_id) DO NOTHING
        """,
            id(1_000 + suffix),
            id(suffix));
    if (inserted == 1) {
      jdbc.update(
          """
          INSERT INTO commerce.inventory_movements
              (id,product_id,operation,business_ref,available_delta,reserved_delta,
               allocated_delta,sold_delta,reason,created_at)
          VALUES (?,?,'SELLER_ADJUSTMENT',?,100,0,0,0,'개발 fixture 초기 재고',now())
          """,
          id(2_000 + suffix),
          id(suffix),
          id(2_000 + suffix));
    }
  }

  private void address(int buyer, int suffix) {
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
             is_default,created_at,updated_at,version)
        VALUES (?,?,?,?,?,NOT EXISTS
            (SELECT 1 FROM commerce.buyer_addresses WHERE buyer_id=? AND deleted_at IS NULL),
            now(),now(),0)
        """,
        id(suffix),
        id(buyer),
        sealed.recipient(),
        sealed.phone(),
        sealed.address(),
        id(buyer));
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
