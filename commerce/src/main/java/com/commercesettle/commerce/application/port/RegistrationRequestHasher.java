package com.commercesettle.commerce.application.port;

/** 가입 본문을 서버 키로 비교 값으로 바꾼다. 비밀번호 원문을 키 없이 대조할 수 있는 해시로 남기지 않기 위해 쓴다. */
public interface RegistrationRequestHasher {
  /**
   * @param emailNormalized 정규화한 이메일
   * @param password 요청 원문 그대로의 비밀번호
   */
  String hash(String emailNormalized, String password, String displayName);
}
