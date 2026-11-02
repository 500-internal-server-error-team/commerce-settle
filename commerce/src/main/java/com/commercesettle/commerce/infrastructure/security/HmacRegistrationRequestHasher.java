package com.commercesettle.commerce.infrastructure.security;

import com.commercesettle.commerce.application.port.RegistrationRequestHasher;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 가입 본문의 HMAC-SHA256. 별도 키를 늘리지 않고 요청 제한의 서버 키를 쓰되, 용도 접두사를 붙여 이메일 제한 키와 같은 값이 나오지 않게 한다. 값마다 길이를
 * 앞에 붙여 경계가 섞이지 않게 한다.
 */
@Component
class HmacRegistrationRequestHasher implements RegistrationRequestHasher {
  private static final String HMAC = "HmacSHA256";
  private static final String PURPOSE = "BUYER_REGISTRATION";

  private final SecretKeySpec key;

  HmacRegistrationRequestHasher(AuthProperties properties) {
    this.key =
        new SecretKeySpec(
            properties.rateLimit().identifierHmacKey().getBytes(StandardCharsets.UTF_8), HMAC);
  }

  @Override
  public String hash(String emailNormalized, String password, String displayName) {
    StringBuilder text = new StringBuilder();
    for (String part : new String[] {PURPOSE, emailNormalized, password, displayName}) {
      text.append(part.length()).append(':').append(part).append(';');
    }
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return HexFormat.of()
          .formatHex(mac.doFinal(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }
}
