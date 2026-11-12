package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataProductRepository extends JpaRepository<Product, UUID> {
  Page<Product> findBySellerIdAndDeletedAtIsNull(UUID sellerId, Pageable pageable);

  Page<Product> findByStatusAndDeletedAtIsNull(ProductStatus status, Pageable pageable);
}
