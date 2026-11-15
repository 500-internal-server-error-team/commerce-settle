# 문제 해결 기록

## Java 21 작업 배정 중 가상 스레드 정체

증상은 작업 스레드가 살아 있으나 처리와 복구 조회가 함께 멎는 것이다. 일반 스레드 덤프만으로는 가상 스레드의 대기 위치가 빠질 수 있으므로 전체 가상 스레드 덤프를 함께 확인한다.

`WorkerSlots`는 상태 모니터를 보유한 채 `ThreadPoolExecutor.execute`를 호출했다. 내부 `ArrayBlockingQueue.offer`는 큐가 가득 차면 거절하지만, 용량을 검사하기 전에 내부 잠금을 획득한다. Java 21에서 이 대기가 모니터 안에서 발생하면 carrier가 pin될 수 있다. 모든 carrier가 점유되면 다른 가상 스레드도 재개하지 못한다.

상태 보호를 하나의 `ReentrantLock`으로 바꿔 큐 잠금 대기 중 carrier를 반환할 수 있게 한다. 슬롯 수·큐 용량·신호 병합·오류 중지·복구·종료 규칙은 유지한다. DB 처리는 기존과 같이 상태 잠금 밖에서 실행한다.

`WorkerSlotsTest.doesNotPinCarriersWhileDispatchWaits`는 두 carrier의 별도 JVM에서 실제 큐 잠금을 제어한다. 작업 확장과 빈 조회 뒤 재배정 중에도 독립 가상 작업이 진행하는지 확인하고, 제어 잠금 해제 뒤 작업·보조 스레드가 종료하는지 검사한다.

이 재현은 코드의 진행성 결함을 검증한다. 실제 장애의 DB 잠금 보유자는 PostgreSQL 트랜잭션·차단 관계와 같은 시각의 JDBC 연결/스레드를 연결해 확인한다. 코드 재현이나 짧은 비교를 배포 환경의 지속 TPS 통과로 해석하지 않는다.

관련: [수정 이슈 #406](https://github.com/500-internal-server-error-team/commerce-settle/issues/406), [배포 실험 #398](https://github.com/500-internal-server-error-team/commerce-settle/issues/398).
