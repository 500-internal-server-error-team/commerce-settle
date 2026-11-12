package com.commercesettle.commerce.domain.exception;

/** 요청이 기대한 버전과 현재 버전이 달라 변경할 수 없다. */
public class VersionConflictException extends RuntimeException {
  public VersionConflictException(String target, long expectedVersion, long currentVersion) {
    super(target + " version expected " + expectedVersion + " but was " + currentVersion);
  }
}
