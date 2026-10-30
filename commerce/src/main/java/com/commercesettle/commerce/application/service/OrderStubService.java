package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.dto.CreateOrderCommand;
import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.exception.OrderStubException;
import com.commercesettle.commerce.application.exception.OrderStubException.Kind;
import com.commercesettle.commerce.application.port.OrderInventory;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 로컬 API 학습용 메모리 stub. 주문 예약·결제·Outbox나 실제 계정/상품 저장소를 호출하지 않는다. 모든 조회 사본은 한 번 만들며, 재시작하면 생성 주문·멱등
 * 결과도 사라진다.
 */
public class OrderStubService {
  private final OrderInventory inventory;
  private final OrderStubDataFactory snapshots;
  private final Map<UUID, OrderView> orders = new HashMap<>();
  private final Map<RequestKey, Created> requests = new HashMap<>();

  public OrderStubService(
      OrderInventory inventory, OrderStubDataFactory snapshots, List<OrderView> initialOrders) {
    this.inventory = inventory;
    this.snapshots = snapshots;
    for (OrderView order : initialOrders) {
      orders.put(order.id(), order);
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
      if (command.externalMethod() != null) {
        throw invalid("externalMethod");
      }
      if (command.methodAccountRef() != null) {
        throw invalid("methodAccountRef");
      }
    } else {
      if (command.externalMethod() == null) {
        throw invalid("externalMethod");
      }
      String account = command.methodAccountRef();
      if (account == null || account.isBlank() || account.length() > 100) {
        throw invalid("methodAccountRef");
      }
    }
    OrderView order = snapshots.pending(buyer, product, command, gross);
    orders.put(order.id(), order);
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

  private static OrderStubException invalid(String field) {
    return new OrderStubException(Kind.INVALID_REQUEST, field);
  }

  private record RequestKey(UUID buyer, String key) {}

  private record Created(CreateOrderCommand command, OrderView order) {}
}
