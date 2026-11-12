package com.commercesettle.settlement.infrastructure.messaging;

import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.settlement.application.port.QuarantineStore.Delivery;
import com.commercesettle.settlement.application.service.QuarantineService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 격리 원문을 DLT로 발행한다. 한 번에 한 작업만 임대하고 스케줄 스레드에서 Kafka 응답을 기다린다(Payment의 전용 실행기는 두지 않는다). 발행 실패는 1초부터
 * 최대 60초 간격으로 10회까지 다시 시도하고, 소진하면 작업을 REVIEW_REQUIRED로 남긴다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.quarantine.enabled", havingValue = "true")
class QuarantineRelay {
  private static final Logger LOGGER = LoggerFactory.getLogger(QuarantineRelay.class);
  private static final int MAX_ATTEMPTS = 10;
  private final RepeatedWarning deliveryRetry = new RepeatedWarning();

  private final QuarantineService service;
  private final KafkaTemplate<String, byte[]> kafka;
  private final int batchSize;
  private final long leaseMs;
  private final long resultTimeoutMs;

  QuarantineRelay(
      QuarantineService service,
      KafkaTemplate<String, byte[]> kafka,
      @Value("${app.kafka.quarantine.batch-size}") int batchSize,
      @Value("${app.kafka.quarantine.lease-ms}") long leaseMs,
      @Value("${app.kafka.quarantine.result-timeout-ms}") long resultTimeoutMs,
      @Value("${spring.kafka.producer.properties.max.block.ms:60000}") long producerMaxBlockMs) {
    // 발행 대기 중 임대가 끝나 다른 실행이 같은 작업을 잡지 않게 한다.
    if (batchSize < 1
        || batchSize > 100
        || resultTimeoutMs < 1
        || producerMaxBlockMs < 0
        || leaseMs <= producerMaxBlockMs + resultTimeoutMs) {
      throw new IllegalArgumentException("Invalid quarantine relay limits");
    }
    this.service = service;
    this.kafka = kafka;
    this.batchSize = batchSize;
    this.leaseMs = leaseMs;
    this.resultTimeoutMs = resultTimeoutMs;
  }

  @Scheduled(fixedDelayString = "${app.kafka.quarantine.interval-ms}")
  void deliver() {
    for (int i = 0; i < batchSize; i++) {
      List<Delivery> claimed = service.claim(1, leaseMs, MAX_ATTEMPTS);
      if (claimed.isEmpty()) {
        return;
      }
      if (!send(claimed.get(0))) {
        return;
      }
    }
  }

  private boolean send(Delivery delivery) {
    var record =
        new ProducerRecord<String, byte[]>(delivery.topic(), delivery.key(), delivery.body());
    record.headers().add("quarantine-id", utf8(delivery.quarantineId().toString()));
    record.headers().add("quarantine-source-topic", utf8(delivery.sourceTopic()));
    record
        .headers()
        .add("quarantine-source-partition", utf8(Integer.toString(delivery.sourcePartition())));
    record.headers().add("quarantine-source-offset", utf8(Long.toString(delivery.sourceOffset())));
    try {
      kafka.send(record).get(resultTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception exception) {
      // 예외·원문을 로그에 싣지 않는다. 임대 안에서 RETRY로 돌리고 다음 주기에 다시 시도한다.
      service.failed(delivery);
      deliveryRetry
          .at(LOGGER)
          .addKeyValue("event", "settlement.quarantine")
          .addKeyValue("result", "RETRY")
          .addKeyValue("jobId", delivery.id())
          .log("Quarantine delivery will be retried");
      return false;
    }
    if (!service.published(delivery)) {
      LOGGER
          .atDebug()
          .addKeyValue("event", "settlement.quarantine")
          .addKeyValue("result", "STALE")
          .addKeyValue("jobId", delivery.id())
          .log("Quarantine delivery lease changed");
    }
    return true;
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
