package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.exception.DefaultAddressConflictException;
import com.commercesettle.commerce.domain.model.BuyerAddress;
import com.commercesettle.commerce.domain.repository.BuyerAddressRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaBuyerAddressRepository implements BuyerAddressRepository {
  private static final String DEFAULT_CONSTRAINT = "uk_buyer_addresses_default";
  private static final Sort DEFAULT_THEN_NEWEST =
      Sort.by(
          Sort.Order.desc("defaultAddress"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataBuyerAddressRepository addresses;
  private final JdbcOperations jdbc;

  JpaBuyerAddressRepository(SpringDataBuyerAddressRepository addresses, JdbcOperations jdbc) {
    this.addresses = addresses;
    this.jdbc = jdbc;
  }

  // 즉시 flush해 기본 주소 유일성 위반을 커밋 시점이 아닌 저장 호출에서 업무 예외로 바꾼다.
  @Override
  public BuyerAddress save(BuyerAddress address) {
    TransactionLimits.apply(jdbc);
    try {
      return addresses.saveAndFlush(address);
    } catch (DataIntegrityViolationException e) {
      if (e.getCause() instanceof ConstraintViolationException violation
          && DEFAULT_CONSTRAINT.equals(violation.getConstraintName())) {
        throw new DefaultAddressConflictException();
      }
      throw e;
    }
  }

  @Override
  public Optional<BuyerAddress> findById(UUID addressId) {
    return addresses.findById(addressId);
  }

  @Override
  public Optional<BuyerAddress> findDefault(UUID buyerId) {
    return addresses.findByBuyerIdAndDefaultAddressTrueAndDeletedAtIsNull(buyerId);
  }

  @Override
  public boolean existsActive(UUID buyerId) {
    return addresses.existsByBuyerIdAndDeletedAtIsNull(buyerId);
  }

  @Override
  public PageResult<BuyerAddress> findByBuyer(UUID buyerId, int page, int size) {
    Page<BuyerAddress> result =
        addresses.findByBuyerIdAndDeletedAtIsNull(
            buyerId, PageRequest.of(page, size, DEFAULT_THEN_NEWEST));
    return new PageResult<>(
        result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
  }
}
