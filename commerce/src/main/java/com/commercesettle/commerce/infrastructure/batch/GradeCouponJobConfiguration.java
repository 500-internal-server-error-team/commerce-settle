package com.commercesettle.commerce.infrastructure.batch;

import com.commercesettle.commerce.application.service.GradeCouponService;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 월간 우수고객 Job. 첫 Step이 대상 확정·자동 검증을, 둘째 Step이 고정한 대상 발급을 맡는다. 둘째 Step의 tasklet은 반복마다 대상 한 명을 처리해
 * 커밋하므로 중단 뒤 같은 식별자로 재시작하면 남은 대상부터 이어서 처리한다. 진행 위치는 업무 DB의 대상 상태가 원본이다.
 */
@Configuration
class GradeCouponJobConfiguration {
  static final String JOB_NAME = "gradeCouponJob";
  static final String TARGET_MONTH = "targetMonth";
  static final String RUN_ID = "runId";

  @Bean
  Job gradeCouponJob(
      JobRepository jobRepository, Step prepareGradeTargets, Step issueGradeCoupons) {
    return new JobBuilder(JOB_NAME, jobRepository)
        .start(prepareGradeTargets)
        .next(issueGradeCoupons)
        .build();
  }

  @Bean
  Step prepareGradeTargets(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      GradeCouponService grades) {
    return new StepBuilder("prepareGradeTargets", jobRepository)
        .tasklet(
            (contribution, context) -> {
              grades.prepare(runId(context.getStepContext().getJobParameters().get(RUN_ID)));
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  @Bean
  Step issueGradeCoupons(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      GradeCouponService grades) {
    return new StepBuilder("issueGradeCoupons", jobRepository)
        .tasklet(
            (contribution, context) -> {
              UUID runId = runId(context.getStepContext().getJobParameters().get(RUN_ID));
              List<UUID> next = grades.pendingTargetIds(runId, 1);
              if (next.isEmpty() || !grades.issue(runId, next.get(0))) {
                grades.finish(runId);
                return RepeatStatus.FINISHED;
              }
              contribution.incrementWriteCount(1);
              return RepeatStatus.CONTINUABLE;
            },
            transactionManager)
        .build();
  }

  private static UUID runId(Object value) {
    return UUID.fromString(String.valueOf(value));
  }
}
