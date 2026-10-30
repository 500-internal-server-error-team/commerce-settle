package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.CreateOrderCommand;
import com.commercesettle.commerce.application.dto.OrderAddressView;
import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.domain.model.Product;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** 개발용 주문 조회 사본과 가상 배송 데이터를 만든다. 실제 주문 상태 전이나 배송 처리는 수행하지 않는다. */
public class OrderStubDataFactory {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final UUID BUYER_A = id(1);
  private static final UUID BUYER_B = id(11);
  private static final int ADDRESS_POOL_SIZE = 1_000;
  private static final List<OrderAddressView> ADDRESSES =
      IntStream.range(0, ADDRESS_POOL_SIZE)
          .mapToObj(
              index ->
                  new OrderAddressView(
                      id(100_000 + index),
                      "테스트 수령인 " + (index + 1),
                      "000-0000-" + String.format("%04d", index + 1),
                      String.format("%05d", index + 1),
                      "가상시 테스트구 샘플로 " + (index / 10 + 1),
                      "테스트동 " + (index + 101) + "호"))
          .toList();
  private final Clock clock;
  private final String seed;

  public OrderStubDataFactory(Clock clock, String seed) {
    this.clock = clock;
    this.seed = seed;
  }

  public List<OrderView> initialOrders(List<Product> products) {
    Instant baseline = now();
    List<OrderView> samples = new ArrayList<>();
    for (UUID buyer : List.of(BUYER_A, BUYER_B)) {
      for (int index = 0; index < 10; index++) {
        samples.add(sample(buyer, index, baseline, products.get(index % products.size())));
      }
    }
    return List.copyOf(samples);
  }

  OrderView pending(UUID buyer, Product product, CreateOrderCommand command, long grossAmount) {
    UUID orderId = UUID.randomUUID();
    return new OrderView(
        orderId,
        buyer,
        product.getSellerId(),
        command.productId(),
        product.getName(),
        command.quantity(),
        grossAmount,
        0,
        grossAmount,
        command.mileageAmount(),
        grossAmount - command.mileageAmount(),
        0,
        0,
        grossAmount,
        null,
        null,
        "PAYMENT_PENDING",
        "UNSHIPPED",
        0,
        now(),
        deliveryDays(orderId),
        "weekdays-v1",
        null,
        null,
        null,
        null,
        null,
        null,
        addressFor(orderId));
  }

  private OrderView sample(UUID buyer, int index, Instant baseline, Product product) {
    UUID orderId = named("order:" + buyer + ":" + index);
    int quantity = 1 + index % 3;
    long gross = product.getUnitPrice() * quantity;
    long total = gross;
    long mileage = index % 3 == 0 ? (index % 2 == 0 ? total : 1_000) : 0;
    int phase = index % 5;
    int days = deliveryDays(orderId);
    Instant created = baseline.minusSeconds(30L + index);
    Instant paid = null;
    Instant shipped = null;
    Instant due = null;
    Instant delivered = null;
    Instant deadline = null;
    Instant confirmed = null;
    String status = "PAYMENT_PENDING";
    String fulfillment = "UNSHIPPED";
    if (phase > 0) {
      status = phase == 4 ? "PURCHASE_CONFIRMED" : "PAID";
      created = baseline.minusSeconds(7_200L + index);
      paid = created.plusSeconds(60);
    }
    if (phase >= 2) {
      fulfillment = "SHIPPED";
      shipped = baseline.minusSeconds(1_800L + index);
      due = businessDays(shipped, days);
    }
    if (phase >= 3) {
      fulfillment = "DELIVERED";
      ZonedDateTime target = baseline.atZone(SEOUL).minusDays(phase == 4 ? 10 : 1);
      while (!weekday(target)) {
        target = target.minusDays(1);
      }
      due = target.toInstant();
      shipped = businessDays(due, -days);
      delivered = due;
      deadline = delivered.plus(168, ChronoUnit.HOURS);
      created = shipped.minusSeconds(120);
      paid = shipped.minusSeconds(60);
      if (phase == 4) {
        confirmed = deadline.plusSeconds(60);
      }
    }
    return new OrderView(
        orderId,
        buyer,
        product.getSellerId(),
        product.getId(),
        product.getName(),
        quantity,
        gross,
        0,
        total,
        mileage,
        total - mileage,
        0,
        0,
        gross,
        null,
        phase == 0 ? null : named("payment:" + orderId),
        status,
        fulfillment,
        phase,
        created,
        days,
        "weekdays-v1",
        paid,
        shipped,
        due,
        delivered,
        deadline,
        confirmed,
        addressFor(orderId));
  }

  private int deliveryDays(UUID orderId) {
    byte[] hash = orderHash(orderId);
    return (hash[hash.length - 1] & 1) == 0 ? 2 : 3;
  }

  private OrderAddressView addressFor(UUID orderId) {
    return ADDRESSES.get(
        Math.floorMod(ByteBuffer.wrap(orderHash(orderId)).getInt(), ADDRESS_POOL_SIZE));
  }

  private byte[] orderHash(UUID orderId) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest((seed + ":" + orderId).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static Instant businessDays(Instant from, int days) {
    ZonedDateTime value = from.atZone(SEOUL);
    int direction = days < 0 ? -1 : 1;
    for (int remaining = Math.abs(days); remaining > 0; ) {
      value = value.plusDays(direction);
      if (weekday(value)) {
        remaining--;
      }
    }
    return value.toInstant();
  }

  private static boolean weekday(ZonedDateTime value) {
    return value.getDayOfWeek() != DayOfWeek.SATURDAY && value.getDayOfWeek() != DayOfWeek.SUNDAY;
  }

  private UUID named(String value) {
    return UUID.nameUUIDFromBytes((seed + ":" + value).getBytes(StandardCharsets.UTF_8));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
