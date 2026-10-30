package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMileageEntryRepository extends JpaRepository<MileageEntry, UUID> {}
