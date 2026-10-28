package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.VersionConflictException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {
  private static final Instant NOW = Instant.parse("2026-10-28T01:00:00.123456789Z");
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

  @Test
  @DisplayName("등록 시각은 마이크로초로 맞추고 버전은 0에서 시작한다")
  void registersWithMicrosecondTimeAndInitialVersion() {
    Product product = register(10_000, ProductStatus.DRAFT);

    assertThat(product.getCreatedAt()).isEqualTo(Instant.parse("2026-10-28T01:00:00.123456Z"));
    assertThat(product.getUpdatedAt()).isEqualTo(product.getCreatedAt());
    assertThat(product.getVersion()).isZero();
    assertThat(product.isPubliclyVisible()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 10_000_000})
  @DisplayName("단가 경계값 1원과 1,000만 원을 허용한다")
  void acceptsUnitPriceBoundaries(long unitPrice) {
    assertThat(register(unitPrice, ProductStatus.ON_SALE).getUnitPrice()).isEqualTo(unitPrice);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 10_000_001})
  @DisplayName("단가가 범위를 벗어나면 등록하지 않는다")
  void rejectsUnitPriceOutOfRange(long unitPrice) {
    assertThatThrownBy(() -> register(unitPrice, ProductStatus.ON_SALE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("이름은 1~120자만 허용한다")
  void rejectsEmptyOrTooLongName() {
    assertThatThrownBy(
            () -> Product.register(UUID.randomUUID(), SELLER, "", 1, ProductStatus.DRAFT, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Product.register(
                    UUID.randomUUID(), SELLER, "가".repeat(121), 1, ProductStatus.DRAFT, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("판매자는 DELETED 상태를 직접 지정할 수 없다")
  void rejectsDeletedStatusOutsideDelete() {
    Product product = register(1_000, ProductStatus.DRAFT);

    assertThatThrownBy(() -> register(1_000, ProductStatus.DELETED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> product.change("상품", 1_000, ProductStatus.DELETED, 0, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("기대 버전이 다르면 변경하지 않는다")
  void keepsProductWhenExpectedVersionDiffers() {
    Product product = register(1_000, ProductStatus.DRAFT);

    assertThatThrownBy(() -> product.change("새 이름", 2_000, ProductStatus.ON_SALE, 1, NOW))
        .isInstanceOf(VersionConflictException.class);
    assertThat(product.getName()).isEqualTo("상품");
    assertThat(product.getUnitPrice()).isEqualTo(1_000);
  }

  @Test
  @DisplayName("변경하면 수정 시각만 갱신하고 판매 중이면 공개 대상이 된다")
  void changesFieldsAndUpdatedTime() {
    Product product = register(1_000, ProductStatus.DRAFT);
    Instant later = NOW.plusSeconds(60);

    product.change("새 이름", 2_000, ProductStatus.ON_SALE, 0, later);

    assertThat(product.getName()).isEqualTo("새 이름");
    assertThat(product.getUnitPrice()).isEqualTo(2_000);
    assertThat(product.getUpdatedAt()).isEqualTo(later.truncatedTo(ChronoUnit.MICROS));
    assertThat(product.getCreatedAt()).isNotEqualTo(product.getUpdatedAt());
    assertThat(product.isPubliclyVisible()).isTrue();
  }

  @Test
  @DisplayName("논리삭제하면 DELETED와 삭제 시각을 남기고 공개하지 않는다")
  void deletesLogically() {
    Product product = register(1_000, ProductStatus.ON_SALE);

    product.delete(0, NOW.plusSeconds(1));

    assertThat(product.getStatus()).isEqualTo(ProductStatus.DELETED);
    assertThat(product.isDeleted()).isTrue();
    assertThat(product.isPubliclyVisible()).isFalse();
  }

  private static Product register(long unitPrice, ProductStatus status) {
    return Product.register(UUID.randomUUID(), SELLER, "상품", unitPrice, status, NOW);
  }
}
