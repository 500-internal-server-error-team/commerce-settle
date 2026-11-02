package com.commercesettle.commerce.infrastructure.client.kakao;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.model.Notification.Channel;
import com.commercesettle.commerce.infrastructure.config.KakaoSelfProperties;
import java.time.Clock;
import java.util.UUID;

/** 새 발송만 설정에 따라 선택한다. 기존 발송의 채널·계정·본문은 변경하지 않는다. */
public final class KakaoSelfNotificationGateway implements NotificationGateway {
  private final NotificationGateway mock;
  private final KakaoSelfMessageClient client;
  private final KakaoSelfProperties properties;
  private final Clock clock;

  public KakaoSelfNotificationGateway(
      NotificationGateway mock,
      KakaoSelfMessageClient client,
      KakaoSelfProperties properties,
      Clock clock) {
    this.mock = mock;
    this.client = client;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public Notification.Destination destination(ActorKind kind, UUID recipient) {
    boolean selected =
        kind == ActorKind.BUYER
            ? properties.buyerIds().contains(recipient)
            : properties.sellerIds().contains(recipient);
    return selected
        ? new Notification.Destination(Channel.KAKAO_SELF, properties.accountRef())
        : Notification.Destination.mock();
  }

  @Override
  public Receipt send(Notification n, int attempt) {
    if (n.channel() == Channel.MOCK_KAKAO) return mock.send(n, attempt);
    var expected = destination(n.recipientKind(), n.recipientId());
    if (expected.channel() != Channel.KAKAO_SELF)
      throw new Failure("KAKAO_RECIPIENT_NOT_ALLOWED", true, false);
    if (!expected.accountRef().equals(n.channelAccountRef()))
      throw new Failure("KAKAO_DESTINATION_CHANGED", true, false);
    return switch (client.send(n.body())) {
      case ACCEPTED -> new Receipt(n.id(), requestHash(n), "ACCEPTED", clock.instant());
      case RETRYABLE -> throw new Failure("KAKAO_TEMPORARILY_UNAVAILABLE", false, false);
      case REJECTED -> throw new Failure("KAKAO_REJECTED", true, false);
      case REAUTHENTICATION_REQUIRED ->
          throw new Failure("KAKAO_REAUTHENTICATION_REQUIRED", true, false);
      case UNKNOWN -> throw new Failure("KAKAO_RESPONSE_UNKNOWN", false, true);
    };
  }

  @Override
  public Receipt lookup(UUID deliveryId, int attempt) {
    return mock.lookup(deliveryId, attempt);
  }

  @Override
  public String requestHash(Notification n) {
    return mock.requestHash(n);
  }
}
