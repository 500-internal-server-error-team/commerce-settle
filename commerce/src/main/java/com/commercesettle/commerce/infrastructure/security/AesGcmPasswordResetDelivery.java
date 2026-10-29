package com.commercesettle.commerce.infrastructure.security;

import com.commercesettle.commerce.application.port.PasswordResetDelivery;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-256-GCM. 암호문은 12바이트 IV 뒤에 암호문과 태그를 이어 붙이며, 토큰 ID를 추가 인증 데이터로 묶는다. */
@Component
class AesGcmPasswordResetDelivery implements PasswordResetDelivery {
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  AesGcmPasswordResetDelivery(AuthProperties properties) {
    // 설정 키는 init_env.py가 만든 256비트 임의 문자열이므로 SHA-256으로 AES 키 길이에 맞춘다.
    this.key = new SecretKeySpec(sha256(properties.passwordReset().deliveryKey()), "AES");
  }

  @Override
  public byte[] seal(UUID tokenId, String token) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    byte[] sealed = crypt(Cipher.ENCRYPT_MODE, iv, tokenId, token.getBytes(StandardCharsets.UTF_8));
    return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
  }

  /** 전달 작업이 쓸 복호화. 키·토큰 ID가 다르거나 암호문이 바뀌면 실패한다. */
  String open(UUID tokenId, byte[] ciphertext) {
    byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
    byte[] sealed = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
    return new String(crypt(Cipher.DECRYPT_MODE, iv, tokenId, sealed), StandardCharsets.UTF_8);
  }

  private byte[] crypt(int mode, byte[] iv, UUID tokenId, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(tokenId.toString().getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Password reset delivery cipher failed", e);
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
