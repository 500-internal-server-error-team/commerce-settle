package com.commercesettle.settlement.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정산 일배치와 지급 Worker 폴링의 스케줄 실행. 각 스케줄러는 자기 설정으로 켜고 끈다. */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {}
