package com.commercesettle.commerce.infrastructure.security;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.domain.model.SealedShippingAddress;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.infrastructure.config.AddressProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * AES-256-GCM. 칸마다 12바이트 IV 뒤에 암호문과 태그를 이어 붙이며, 주소 ID와 칸 이름을 추가 인증 데이터로 묶어 다른 행·칸으로 옮긴 암호문을 거절한다.
 * 주소 칸은 우편번호·주소 두 줄을 길이 접두 UTF 문자열로 이어 암호화한다.
 */
@Component
class AesGcmAddressCipher implements AddressCipher {
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  AesGcmAddressCipher(AddressProperties properties) {
    // 설정 키는 init_env.py가 만든 256비트 임의 문자열이므로 SHA-256으로 AES 키 길이에 맞춘다.
    this.key = new SecretKeySpec(sha256(properties.encryptionKey()), "AES");
  }

  @Override
  public SealedShippingAddress seal(UUID addressId, ShippingAddress address) {
    return new SealedShippingAddress(
        seal(addressId, "recipient", utf8(address.recipient())),
        seal(addressId, "phone", utf8(address.phone())),
        seal(
            addressId,
            "address",
            join(address.postalCode(), address.addressLine1(), address.addressLine2())));
  }

  @Override
  public ShippingAddress open(UUID addressId, SealedShippingAddress sealed) {
    String[] lines = split(open(addressId, "address", sealed.address()), 3);
    return new ShippingAddress(
        new String(open(addressId, "recipient", sealed.recipient()), StandardCharsets.UTF_8),
        new String(open(addressId, "phone", sealed.phone()), StandardCharsets.UTF_8),
        lines[0],
        lines[1],
        lines[2]);
  }

  private byte[] seal(UUID addressId, String field, byte[] plaintext) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    byte[] sealed = crypt(Cipher.ENCRYPT_MODE, iv, addressId, field, plaintext);
    return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
  }

  private byte[] open(UUID addressId, String field, byte[] ciphertext) {
    byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
    byte[] sealed = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
    return crypt(Cipher.DECRYPT_MODE, iv, addressId, field, sealed);
  }

  private byte[] crypt(int mode, byte[] iv, UUID addressId, String field, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(utf8(addressId + ":" + field));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Buyer address cipher failed", e);
    }
  }

  private static byte[] join(String... values) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      for (String value : values) {
        out.writeUTF(value);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static String[] split(byte[] joined, int count) {
    String[] values = new String[count];
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(joined))) {
      for (int i = 0; i < count; i++) {
        values[i] = in.readUTF();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return values;
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
