package com.commercesettle.financemock.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시험 시작 때 준비하는 지급 재원 계정과 기본 지급 시나리오. 선지급 재원은 항상 충분하다는 전제를 위해 시험 전체 지급액을 감당할 기초잔액을 준다.
 *
 * @param accountRef PAYOUT 재원 계정 참조. Payment·Settlement가 같은 값을 사용한다
 * @param openingBalance 계정을 처음 만들 때의 기초잔액(원)
 * @param scenarioVersion 장애를 주입하지 않는 정상 지급 시나리오 버전
 * @param seed 정상 시나리오의 결과 seed
 */
@ConfigurationProperties("finance-mock.payout-fixture")
record PayoutFixtureProperties(
    String accountRef, long openingBalance, String scenarioVersion, long seed) {}
