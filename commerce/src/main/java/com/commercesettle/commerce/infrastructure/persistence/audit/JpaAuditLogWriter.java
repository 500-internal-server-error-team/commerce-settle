package com.commercesettle.commerce.infrastructure.persistence.audit;

import com.commercesettle.commerce.application.port.AuditLog;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 업무 변경과 함께 커밋·롤백되도록 진행 중인 트랜잭션에서만 쓴다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JpaAuditLogWriter implements AuditLog {
  private final EntityManager entityManager;

  JpaAuditLogWriter(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public void record(Entry entry) {
    entityManager.persist(new JpaAuditLog(entry));
  }
}
