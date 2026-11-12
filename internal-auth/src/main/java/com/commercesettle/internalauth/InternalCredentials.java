package com.commercesettle.internalauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 발신자별 내부 호출 자격을 확인한다. 발신자마다 현재·이전 두 값까지 허용한다.
 *
 * <p>설정 원문 대신 SHA-256 결과만 보관하고 {@link MessageDigest#isEqual}로 비교한다. 자격이 256비트(URL-safe 43자) 미만이거나
 * 발신자 ID 형식이 다르면 기동하지 않는다.
 */
public final class InternalCredentials {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43,}");
  private static final Pattern SENDER_ID =
      Pattern.compile("gateway|service:[a-z][a-z0-9-]*|operator:[A-Za-z0-9._-]+");

  private final List<Entry> entries;

  /**
   * @param credentials 발신자 ID별 자격. 각 목록은 현재 값과 선택적인 이전 값이다.
   * @param required 반드시 설정해야 하는 발신자 ID
   */
  public InternalCredentials(Map<String, List<String>> credentials, String required) {
    if (!credentials.containsKey(required)) {
      throw new IllegalStateException("Missing internal credential for " + required);
    }
    final var entries = new ArrayList<Entry>();
    credentials.forEach(
        (sender, values) -> {
          if (!SENDER_ID.matcher(sender).matches()) {
            throw new IllegalStateException("Unsupported internal sender: " + sender);
          }
          if (values.isEmpty() || values.size() > 2) {
            throw new IllegalStateException("Internal sender needs one or two credentials");
          }
          for (var value : values) {
            entries.add(new Entry(sender, digest(requireFormat(sender, value))));
          }
        });
    this.entries = List.copyOf(entries);
  }

  /**
   * 자격이 URL-safe 43자(256비트) 이상인지 확인한다. 보내는 쪽도 기동 때 같은 기준으로 확인한다. 오류 메시지에 원문을 넣지 않는다.
   *
   * @param sender 자격을 쓰는 발신자 ID
   * @return 확인한 자격
   */
  public static String requireFormat(String sender, String value) {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalStateException(
          "Internal credential for " + sender + " must be URL-safe and at least 43 chars");
    }
    return value;
  }

  /** 자격과 일치하는 발신자 ID. 모든 등록 값과 비교해 일치 위치에 따라 시간이 달라지지 않게 한다. */
  public Optional<String> sender(String credential) {
    final var presented = digest(credential);
    String matched = null;
    for (var entry : entries) {
      if (MessageDigest.isEqual(entry.digest(), presented) && matched == null) {
        matched = entry.sender();
      }
    }
    return Optional.ofNullable(matched);
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Entry(String sender, byte[] digest) {}
}
