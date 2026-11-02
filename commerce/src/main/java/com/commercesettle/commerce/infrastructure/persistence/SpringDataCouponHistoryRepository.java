package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCouponHistoryRepository extends JpaRepository<CouponHistory, UUID> {}
