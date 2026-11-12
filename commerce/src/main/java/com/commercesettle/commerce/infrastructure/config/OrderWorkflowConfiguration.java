package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.OrderResultService;
import com.commercesettle.commerce.application.service.OrderWorkflowService;
import com.commercesettle.commerce.infrastructure.messaging.PaymentAcceptedProcessor;
import com.commercesettle.observability.RepeatedWarning;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class OrderWorkflowConfiguration {
  @Bean
  @ConditionalOnProperty(name = "app.order-workflow.enabled", havingValue = "true")
  OrderClock orderClock(OrderWorkflowService workflow) {
    return new OrderClock(workflow);
  }

  @Bean
  @ConditionalOnProperty(name = "app.order-result-processor.enabled", havingValue = "true")
  OrderResults orderResults(OrderResultService results) {
    return new OrderResults(results);
  }

  static class OrderClock {
    private final OrderWorkflowService workflow;
    private final RepeatedWarning deferred = new RepeatedWarning();

    OrderClock(OrderWorkflowService workflow) {
      this.workflow = workflow;
    }

    @Scheduled(
        initialDelayString = "${app.order-workflow.interval-ms:60000}",
        fixedDelayString = "${app.order-workflow.interval-ms:60000}")
    public void run() {
      java.util.UUID after = null;
      while (true) {
        var due = workflow.due(100, after);
        if (due.isEmpty()) return;
        for (var id : due) {
          try {
            workflow.advance(id);
          } catch (RuntimeException e) {
            deferred.at(LoggerFactory.getLogger(OrderClock.class)).log(
                "Order clock deferred: orderId={}", id);
          }
        }
        after = due.getLast();
      }
    }
  }

  static class OrderResults {
    private final OrderResultService results;
    private final RepeatedWarning paused = new RepeatedWarning();

    OrderResults(OrderResultService results) {
      this.results = results;
    }

    @Scheduled(
        initialDelayString = "${app.order-result-processor.interval-ms:1000}",
        fixedDelayString = "${app.order-result-processor.interval-ms:1000}")
    public void run() {
      try {
        PaymentAcceptedProcessor.drain(results::processNext, 100);
      } catch (RuntimeException e) {
        paused.at(LoggerFactory.getLogger(OrderResults.class)).log(
            "Order result processing paused: errorCode=INBOX_UNAVAILABLE");
      }
    }
  }
}
