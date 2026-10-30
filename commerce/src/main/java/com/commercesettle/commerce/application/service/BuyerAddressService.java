package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.domain.exception.BuyerAddressNotFoundException;
import com.commercesettle.commerce.domain.model.BuyerAddress;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.BuyerAddressRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 주소록 유즈케이스. 인증된 구매자와 주소 소유자를 대조하며, 타인·삭제 주소는 존재하지 않는 주소와 같게 취급한다. 첫 주소는 요청과 관계없이 기본 주소가 되고, 다른
 * 주소를 기본으로 지정하면 같은 트랜잭션에서 기존 기본을 해제한다. 쓰기 작업은 멱등 기록·업무 변경을 한 트랜잭션에 저장하며, 구매자 계정이 없거나 삭제됐으면 인증 실패로 둘
 * 다 롤백한다.
 */
@Service
public class BuyerAddressService {
  private static final String BUYER = "BUYER";

  private final AccountRepository accounts;
  private final BuyerAddressRepository addresses;
  private final AddressCipher cipher;
  private final IdempotentRequests idempotency;
  private final Clock clock;

  public BuyerAddressService(
      AccountRepository accounts,
      BuyerAddressRepository addresses,
      AddressCipher cipher,
      IdempotentRequests idempotency,
      Clock clock) {
    this.accounts = accounts;
    this.addresses = addresses;
    this.cipher = cipher;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResult<StoredAddress> addresses(UUID buyerId, int page, int size) {
    PageResult<BuyerAddress> result = addresses.findByBuyer(buyerId, page, size);
    return new PageResult<>(
        result.items().stream().map(this::open).toList(),
        result.page(),
        result.size(),
        result.totalElements());
  }

  @Transactional
  public StoredAddress register(
      UUID buyerId, String idempotencyKey, ShippingAddress shipping, boolean makeDefault) {
    UUID addressId = UUID.randomUUID();
    return idempotency.execute(
        buyerRequest(buyerId, "ADDRESS_REGISTER", idempotencyKey, body(shipping, makeDefault)),
        addressId,
        201,
        recordId -> {
          lockBuyer(buyerId);
          Instant now = clock.instant();
          boolean first = !addresses.existsActive(buyerId);
          if (makeDefault && !first) {
            clearCurrentDefault(buyerId, now);
          }
          BuyerAddress address =
              BuyerAddress.register(
                  addressId, buyerId, cipher.seal(addressId, shipping), first || makeDefault, now);
          return open(addresses.save(address));
        },
        existingId -> open(ownedAddress(buyerId, existingId)));
  }

  @Transactional
  public StoredAddress change(
      UUID buyerId,
      UUID addressId,
      String idempotencyKey,
      ShippingAddress shipping,
      boolean makeDefault,
      long expectedVersion) {
    return idempotency.execute(
        buyerRequest(
            buyerId,
            "ADDRESS_CHANGE",
            idempotencyKey,
            body(shipping, makeDefault, addressId, expectedVersion)),
        addressId,
        200,
        recordId -> {
          lockBuyer(buyerId);
          Instant now = clock.instant();
          BuyerAddress address = ownedAddress(buyerId, addressId);
          if (makeDefault && !address.isDefault()) {
            clearCurrentDefault(buyerId, now);
          }
          address.change(cipher.seal(addressId, shipping), makeDefault, expectedVersion, now);
          return open(addresses.save(address));
        },
        existingId -> open(ownedAddress(buyerId, existingId)));
  }

  @Transactional
  public void delete(UUID buyerId, UUID addressId, String idempotencyKey, long expectedVersion) {
    idempotency.execute(
        buyerRequest(
            buyerId, "ADDRESS_DELETE", idempotencyKey, List.of(addressId, expectedVersion)),
        addressId,
        204,
        recordId -> {
          lockBuyer(buyerId);
          BuyerAddress address = ownedAddress(buyerId, addressId);
          address.delete(expectedVersion, clock.instant());
          return addresses.save(address);
        },
        existingId -> null);
  }

  // 첫 주소 판단과 기본 주소 이동을 읽기 전에 구매자 행을 잠가 같은 구매자의 주소록 쓰기를 직렬화한다.
  private void lockBuyer(UUID buyerId) {
    accounts.findBuyerForUpdate(buyerId).orElseThrow(AuthenticationFailedException::new);
  }

  // 새 기본 주소보다 먼저 flush해 기본 주소 부분 UNIQUE를 지나게 한다.
  private void clearCurrentDefault(UUID buyerId, Instant now) {
    addresses
        .findDefault(buyerId)
        .ifPresent(
            current -> {
              current.clearDefault(now);
              addresses.save(current);
            });
  }

  private BuyerAddress ownedAddress(UUID buyerId, UUID addressId) {
    return addresses
        .findById(addressId)
        .filter(address -> !address.isDeleted() && address.isOwnedBy(buyerId))
        .orElseThrow(() -> new BuyerAddressNotFoundException(addressId));
  }

  private StoredAddress open(BuyerAddress address) {
    return new StoredAddress(
        address.getId(),
        cipher.open(address.getId(), address.getSealed()),
        address.isDefault(),
        address.getVersion());
  }

  // 값마다 길이를 붙여 비교하도록 중첩하지 않은 목록으로 만든다.
  private static List<Object> body(
      ShippingAddress shipping, boolean makeDefault, Object... target) {
    List<Object> body = new ArrayList<>(List.of(target));
    body.addAll(
        List.of(
            shipping.recipient(),
            shipping.phone(),
            shipping.postalCode(),
            shipping.addressLine1(),
            shipping.addressLine2(),
            makeDefault));
    return body;
  }

  private static IdempotentRequests.Request buyerRequest(
      UUID buyerId, String operation, String idempotencyKey, List<?> body) {
    return new IdempotentRequests.Request(
        BUYER, buyerId.toString(), operation, idempotencyKey, body);
  }

  /** 복호화한 주소록 한 건. */
  public record StoredAddress(UUID id, ShippingAddress shipping, boolean isDefault, long version) {}
}
