package com.commercesettle.commerce.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfMessageClient;
import com.commercesettle.commerce.infrastructure.persistence.notification.NotificationReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KakaoSelfConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(KakaoSelfConfiguration.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  @DisplayName("기본 설정에는 실제 카카오 클라이언트가 없다")
  void leavesRealSendingDisabledByDefault() {
    context.run(
        application -> {
          assertThat(application).doesNotHaveBean(KakaoSelfMessageClient.class);
          var gateway =
              new NotificationConfiguration()
                  .notificationGateway(
                      mock(NotificationReceiptStore.class),
                      Clock.systemUTC(),
                      application.getBeanProvider(KakaoSelfMessageClient.class),
                      application.getBeanProvider(KakaoSelfProperties.class),
                      67,
                      0,
                      0,
                      0,
                      0);
          assertThat(gateway.destination(ActorKind.BUYER, UUID.randomUUID()))
              .isEqualTo(Notification.Destination.mock());
        });
  }

  @Test
  @DisplayName("실제 발송을 켜도 수신 계정과 자격이 없으면 기동하지 않는다")
  void requiresExplicitDemoTarget() {
    context
        .withPropertyValues("app.kakao-self.enabled=true")
        .run(application -> assertThat(application).hasFailed());
  }

  @Test
  @DisplayName("구매자·판매자 시험 계정을 명시한 경우에만 실제 클라이언트를 만든다")
  void acceptsBoundOwnerAndTestRecipients() {
    context
        .withPropertyValues(
            "app.kakao-self.enabled=true",
            "app.kakao-self.credentials-file=/run/kakao/credentials.json",
            "app.kakao-self.user-id=123",
            "app.kakao-self.app-id=456",
            "app.kakao-self.message-link=https://example.test/api/orders",
            "app.kakao-self.buyer-ids=00000000-0000-4000-8000-000000000001",
            "app.kakao-self.seller-ids=00000000-0000-4000-8000-000000000002")
        .run(
            application -> {
              assertThat(application).hasSingleBean(KakaoSelfMessageClient.class);
              var gateway =
                  new NotificationConfiguration()
                      .notificationGateway(
                          mock(NotificationReceiptStore.class),
                          Clock.systemUTC(),
                          application.getBeanProvider(KakaoSelfMessageClient.class),
                          application.getBeanProvider(KakaoSelfProperties.class),
                          67,
                          0,
                          0,
                          0,
                          0);
              UUID buyer = UUID.fromString("00000000-0000-4000-8000-000000000001");
              UUID seller = UUID.fromString("00000000-0000-4000-8000-000000000002");
              var destination =
                  new Notification.Destination(
                      Notification.Channel.KAKAO_SELF,
                      application.getBean(KakaoSelfProperties.class).accountRef());
              assertThat(gateway.destination(ActorKind.BUYER, buyer)).isEqualTo(destination);
              assertThat(gateway.destination(ActorKind.SELLER, seller)).isEqualTo(destination);
              assertThat(gateway.destination(ActorKind.SELLER, buyer))
                  .isEqualTo(Notification.Destination.mock());
              assertThat(gateway.destination(ActorKind.BUYER, seller))
                  .isEqualTo(Notification.Destination.mock());
              assertThat(gateway.destination(ActorKind.BUYER, UUID.randomUUID()))
                  .isEqualTo(Notification.Destination.mock());
            });
  }
}
