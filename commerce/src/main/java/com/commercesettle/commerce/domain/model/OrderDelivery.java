package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Embeddable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 생성 당시 seed·배송 소요일·달력을 고정한다. 배송 상태나 완료 시각은 바꾸지 않는다. */
@Embeddable
public class OrderDelivery {
  private long simulationSeed;
  private int deliveryBusinessDays;
  private String deliveryCalendarVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  private Calendar deliveryCalendar;

  protected OrderDelivery() {}

  private OrderDelivery(UUID orderId, long seed, String calendarVersion, List<LocalDate> holidays) {
    Objects.requireNonNull(orderId, "orderId");
    if (calendarVersion == null || calendarVersion.isBlank() || calendarVersion.length() > 32) {
      throw new IllegalArgumentException("Invalid delivery calendar version");
    }
    this.simulationSeed = seed;
    this.deliveryCalendarVersion = calendarVersion;
    this.deliveryCalendar = new Calendar("Asia/Seoul", holidays);
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((seed + ":" + orderId).getBytes(StandardCharsets.UTF_8));
      this.deliveryBusinessDays = (hash[hash.length - 1] & 1) == 0 ? 2 : 3;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public static OrderDelivery capture(
      UUID orderId, long seed, String calendarVersion, List<LocalDate> holidays) {
    return new OrderDelivery(orderId, seed, calendarVersion, holidays);
  }

  public long getSimulationSeed() {
    return simulationSeed;
  }

  public int getDeliveryBusinessDays() {
    return deliveryBusinessDays;
  }

  public String getDeliveryCalendarVersion() {
    return deliveryCalendarVersion;
  }

  public Calendar getDeliveryCalendar() {
    return deliveryCalendar;
  }

  public record Calendar(String timeZone, List<LocalDate> holidays) {
    public Calendar {
      if (!"Asia/Seoul".equals(timeZone)) {
        throw new IllegalArgumentException("Delivery calendar must use Asia/Seoul");
      }
      holidays = List.copyOf(holidays);
    }
  }
}
