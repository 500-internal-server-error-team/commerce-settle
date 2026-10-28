package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
class JpaProductRepository implements ProductRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataProductRepository products;

  JpaProductRepository(SpringDataProductRepository products) {
    this.products = products;
  }

  @Override
  public Product save(Product product) {
    return products.saveAndFlush(product);
  }

  @Override
  public Optional<Product> findById(UUID productId) {
    return products.findById(productId);
  }

  @Override
  public PageResult<Product> findBySeller(UUID sellerId, int page, int size) {
    return toResult(products.findBySellerIdAndDeletedAtIsNull(sellerId, newestFirst(page, size)));
  }

  @Override
  public PageResult<Product> findOnSale(int page, int size) {
    return toResult(
        products.findByStatusAndDeletedAtIsNull(ProductStatus.ON_SALE, newestFirst(page, size)));
  }

  private static Pageable newestFirst(int page, int size) {
    return PageRequest.of(page, size, NEWEST_FIRST);
  }

  private static PageResult<Product> toResult(Page<Product> page) {
    return new PageResult<>(
        page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
  }
}
