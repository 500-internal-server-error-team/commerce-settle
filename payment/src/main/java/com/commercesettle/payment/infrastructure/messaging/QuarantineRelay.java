package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.service.QuarantineService;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** 한 번에 한 작업만 임대한다. Kafka 응답 대기 중에는 DB 트랜잭션을 열지 않는다. */
public class QuarantineRelay {
  private static final Logger LOG = LoggerFactory.getLogger(QuarantineRelay.class);
  private final QuarantineService service;
  private final KafkaTemplate<String, byte[]> kafka;
  private final int batchSize;
  private final long leaseMs;
  private final long resultTimeoutMs;

  public QuarantineRelay(
      QuarantineService service,
      KafkaTemplate<String, byte[]> kafka,
      int batchSize,
      long leaseMs,
      long resultTimeoutMs,
      long producerMaxBlockMs) {
    if (batchSize < 1
        || batchSize > 100
        || resultTimeoutMs < 1
        || producerMaxBlockMs < 0
        || leaseMs <= producerMaxBlockMs
        || resultTimeoutMs >= leaseMs - producerMaxBlockMs) {
      throw new IllegalArgumentException("Invalid quarantine relay limits");
    }
    this.service = service;
    this.kafka = kafka;
    this.batchSize = batchSize;
    this.leaseMs = leaseMs;
    this.resultTimeoutMs = resultTimeoutMs;
  }

  @Scheduled(
      initialDelayString = "${app.kafka.quarantine.interval-ms:1000}",
      fixedDelayString = "${app.kafka.quarantine.interval-ms:1000}")
  public void run() {
    for (int i = 0; i < batchSize; i++) {
      var claimed = service.claim(1, leaseMs, 10);
      if (claimed.isEmpty()) {
        return;
      }
      var delivery = claimed.getFirst();
      var record =
          new ProducerRecord<String, byte[]>(delivery.topic(), delivery.key(), delivery.body());
      record.headers().add("quarantine-id", utf8(delivery.quarantineId().toString()));
      record.headers().add("quarantine-source-topic", utf8(delivery.sourceTopic()));
      record
          .headers()
          .add("quarantine-source-partition", utf8(Integer.toString(delivery.sourcePartition())));
      record
          .headers()
          .add("quarantine-source-offset", utf8(Long.toString(delivery.sourceOffset())));
      try {
        kafka.send(record).get(resultTimeoutMs, TimeUnit.MILLISECONDS);
        if (!service.published(delivery)) {
          LOG.atWarn()
              .addKeyValue("event", "quarantine.stale-result")
              .addKeyValue("jobId", delivery.id())
              .log("Quarantine delivery lease changed");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception exception) {
        // 예외·payload 원문을 로그에 싣지 않는다. 발행 성공 후 저장 실패도 원문으로 재개한다.
        service.failed(delivery);
        LOG.atWarn()
            .addKeyValue("event", "quarantine.delivery-retry")
            .addKeyValue("jobId", delivery.id())
            .log("Quarantine delivery will be retried");
        return;
      }
    }
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
