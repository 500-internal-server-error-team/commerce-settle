package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.VersionConflictException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuyerAddressTest {
  private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
  private static final SealedShippingAddress SEALED =
      new SealedShippingAddress(new byte[] {1}, new byte[] {2}, new byte[] {3});

  @Test
  @DisplayName("기본 주소에 기본 해제를 요청해도 다른 주소를 지정하기 전까지 기본으로 남는다")
  void keepsDefaultUntilAnotherIsChosen() {
    BuyerAddress address =
        BuyerAddress.register(UUID.randomUUID(), UUID.randomUUID(), SEALED, true, NOW);

    address.change(SEALED, false, 0, NOW);

    assertThat(address.isDefault()).isTrue();
  }

  @Test
  @DisplayName("삭제한 주소는 기본이 아니며 삭제 시각을 남긴다")
  void deleteClearsDefault() {
    BuyerAddress address =
        BuyerAddress.register(UUID.randomUUID(), UUID.randomUUID(), SEALED, true, NOW);

    address.delete(0, NOW);

    assertThat(address.isDefault()).isFalse();
    assertThat(address.isDeleted()).isTrue();
  }

  @Test
  @DisplayName("기대 버전이 다르면 변경·삭제를 거절한다")
  void rejectsStaleVersion() {
    BuyerAddress address =
        BuyerAddress.register(UUID.randomUUID(), UUID.randomUUID(), SEALED, false, NOW);

    assertThatThrownBy(() -> address.change(SEALED, true, 1, NOW))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> address.delete(1, NOW)).isInstanceOf(VersionConflictException.class);
    assertThat(address.isDefault()).isFalse();
  }

  @Test
  @DisplayName("배송 정보 값은 문자열 표현에 노출하지 않는다")
  void redactsShippingAddress() {
    ShippingAddress shipping = new ShippingAddress("홍길동", "010-0000-0000", "06236", "서울", "");

    assertThat(shipping.toString()).doesNotContain("홍길동", "010", "06236");
  }
}
