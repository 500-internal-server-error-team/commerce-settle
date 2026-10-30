package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.CreateOrderCommand;
import com.commercesettle.commerce.application.dto.OrderAddressView;
import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.exception.OrderStubException;
import com.commercesettle.commerce.application.exception.OrderStubException.Kind;
import com.commercesettle.commerce.application.port.OrderInventory;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.repository.PageResult;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * 로컬 API 학습용 메모리 stub. 주문 예약·결제·Outbox나 실제 계정/상품 저장소를 호출하지 않는다. 모든 조회 사본은 한 번 만들며, 재시작하면 생성 주문·멱등
 * 결과도 사라진다.
 */
public class OrderStubService {
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
  private final OrderInventory inventory;
  private final Map<UUID, OrderView> orders = new HashMap<>();
  private final Map<RequestKey, Created> requests = new HashMap<>();

  public OrderStubService(
      Clock clock, String seed, OrderInventory inventory, List<Product> sampleProducts) {
    this.clock = clock;
    this.seed = seed;
    this.inventory = inventory;
    Instant baseline = now();
    for (UUID buyer : List.of(BUYER_A, BUYER_B)) {
      for (int index = 0; index < 10; index++) {
        OrderView order =
            sample(buyer, index, baseline, sampleProducts.get(index % sampleProducts.size()));
        orders.put(order.id(), order);
      }
    }
  }

  public synchronized OrderView create(UUID buyer, String key, CreateOrderCommand command) {
    RequestKey requestKey = new RequestKey(buyer, key);
    Created existing = requests.get(requestKey);
    if (existing != null) {
      if (!existing.command().equals(command)) {
        throw new IdempotencyConflictException("Different order request for the same key");
      }
      return existing.order();
    }
    if (command.quantity() < 1 || command.quantity() > 10) {
      throw invalid("quantity");
    }
    Product product = inventory.lockInventoryAndReadProduct(command.productId());
    if (!product.isPubliclyVisible()) {
      throw new ProductNotFoundException(command.productId());
    }
    if (command.expectedProductVersion() != product.getVersion()) {
      throw new VersionConflictException(
          "product", command.expectedProductVersion(), product.getVersion());
    }
    // TODO: 쿠폰·이벤트가 구현되면 검증·예약 포트를 연결한다. 현재 할인과 이벤트는 지원하지 않는다.
    long gross = product.getUnitPrice() * command.quantity();
    long total = gross;
    if (command.expectedTotalAmount() != total) {
      throw new VersionConflictException("totalAmount", command.expectedTotalAmount(), total);
    }
    if (command.mileageAmount() < 0 || command.mileageAmount() > total) {
      throw invalid("mileageAmount");
    }
    long external = total - command.mileageAmount();
    if (external == 0) {
      if (command.externalMethod() != null || command.methodAccountRef() != null) {
        throw invalid("externalMethod");
      }
    } else if (!List.of("CREDIT_CARD", "DEBIT_CARD", "EASY_PAY")
            .contains(command.externalMethod() == null ? "" : command.externalMethod())
        || command.methodAccountRef() == null
        || command.methodAccountRef().isBlank()
        || command.methodAccountRef().length() > 100) {
      throw invalid("externalMethod");
    }
    UUID orderId = UUID.randomUUID();
    OrderView order =
        new OrderView(
            orderId,
            buyer,
            product.getSellerId(),
            command.productId(),
            product.getName(),
            command.quantity(),
            gross,
            0,
            total,
            command.mileageAmount(),
            external,
            0,
            0,
            gross,
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
    orders.put(orderId, order);
    requests.put(requestKey, new Created(command, order));
    return order;
  }

  public synchronized PageResult<OrderView> list(UUID buyer, int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw invalid("page");
    }
    List<OrderView> own =
        orders.values().stream()
            .filter(o -> o.buyerId().equals(buyer))
            .sorted(
                Comparator.comparing(OrderView::createdAt)
                    .thenComparing(o -> o.id().toString())
                    .reversed())
            .toList();
    int start = (int) Math.min((long) page * size, own.size());
    int end = Math.min(start + size, own.size());
    return new PageResult<>(own.subList(start, end), page, size, own.size());
  }

  public synchronized OrderView get(UUID buyer, UUID orderId) {
    OrderView order = orders.get(orderId);
    if (order == null || !order.buyerId().equals(buyer)) {
      throw new OrderStubException(Kind.NOT_FOUND, "orderId");
    }
    return order;
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

  private static OrderStubException invalid(String field) {
    return new OrderStubException(Kind.INVALID_REQUEST, field);
  }

  private record RequestKey(UUID buyer, String key) {}

  private record Created(CreateOrderCommand command, OrderView order) {}
}
