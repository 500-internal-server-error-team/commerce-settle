# 문제 해결 기록

## Java 21 작업 배정 중 가상 스레드 정체

증상은 작업 스레드가 살아 있으나 처리와 복구 조회가 함께 멎는 것이다. 일반 스레드 덤프만으로는 가상 스레드의 대기 위치가 빠질 수 있으므로 전체 가상 스레드 덤프를 함께 확인한다.

`WorkerSlots`는 상태 모니터를 보유한 채 `ThreadPoolExecutor.execute`를 호출했다. 내부 `ArrayBlockingQueue.offer`는 큐가 가득 차면 거절하지만, 용량을 검사하기 전에 내부 잠금을 획득한다. Java 21에서 이 대기가 모니터 안에서 발생하면 carrier가 pin될 수 있다. 모든 carrier가 점유되면 다른 가상 스레드도 재개하지 못한다.

상태 보호를 하나의 `ReentrantLock`으로 바꿔 큐 잠금 대기 중 carrier를 반환할 수 있게 한다. 슬롯 수·큐 용량·신호 병합·오류 중지·복구·종료 규칙은 유지한다. DB 처리는 기존과 같이 상태 잠금 밖에서 실행한다.

`WorkerSlotsTest.doesNotPinCarriersWhileDispatchWaits`는 두 carrier의 별도 JVM에서 실제 큐 잠금을 제어한다. 작업 확장과 빈 조회 뒤 재배정 중에도 독립 가상 작업이 진행하는지 확인하고, 제어 잠금 해제 뒤 작업·보조 스레드가 종료하는지 검사한다.

이 재현은 코드의 진행성 결함을 검증한다. 실제 장애의 DB 잠금 보유자는 PostgreSQL 트랜잭션·차단 관계와 같은 시각의 JDBC 연결/스레드를 연결해 확인한다. 코드 재현이나 짧은 비교를 배포 환경의 지속 TPS 통과로 해석하지 않는다.

관련: [수정 이슈 #406](https://github.com/500-internal-server-error-team/commerce-settle/issues/406), [배포 실험 #398](https://github.com/500-internal-server-error-team/commerce-settle/issues/398).

## 배포 부하 시험의 주문 배출 관측

구매자의 전체 주문 목록을 페이지별로 훑으면 과거 거래가 많을수록 이번 시험의 배출 확인 예산을 소진할 수 있다. 배포 환경에서는 `tests/load/experiment.py --cohort-data-ssh user@data-host`로 이번 시험에서 새로 만든 상품의 주문만 PostgreSQL에서 읽는다. 기존 SSH 키는 `LOAD_SSH_KEY`, 데이터 호스트의 검증된 known-hosts 파일은 `LOAD_DATA_SSH_KNOWN_HOSTS`로 지정한다. DB 자격은 데이터 호스트의 기존 PostgreSQL 컨테이너에서 재사용하며 외부로 내보내지 않는다.

관측 연결은 읽기 전용이고 조회 시간에 상한을 둔다. SSH·조회·응답 검증 실패를 완료로 처리하거나 전체 주문 조회로 되돌리지 않는다. HTTP 접수가 불명확한 요청은 그대로 남기며, 주문 종결 관측은 재고·결제·정산의 금융 대조를 대신하지 않는다. `--check-only`도 같은 데이터 호스트와 읽기 전용 조회를 먼저 검증한다.

## 지급 총모집단 지표 수집 시간 초과

`payout.total`은 미지급 항목마다 원본 확정시각의 상관 서브쿼리를 반복했다. 항목이 늘면 기존 2초 제한을 넘겨 원천 시각이 갱신되지 않고 critical 알림으로 배포 실험이 차단된다. 원본 `SELLER` 원장과 적용된 `PaymentConfirmed` Inbox를 연결해 항목별 첫 원본을 집합 조회한다. 이벤트·원장·Inbox의 기존 정렬 순서와 누락·환불·모집단 분류를 유지한다. 조회 제한이나 critical 차단을 완화하지 않는다.

`BusinessMetricsIntegrationTest.payoutTotalDoesNotRepeatOriginLookupForEveryUnpaidItem`은 실제 PostgreSQL 실행계획에서 32개 항목에 대해 원본 조회가 32회 반복되는 실패를 재현한다. 기존 모집단·원장·누락 검증도 함께 실행한다. 관측 SQL의 비용 변화는 WorkerSlots 수정 전후 부하 비교의 조건 차이에 포함한다.

## 정식 금융 대조의 운영자 설정 부재

현재 Settlement에 운영자 설정이 없으면 [운영자 준비 계약](development.md#정산-대조-실행과-재개)에 따라 별도의 운영자 자격을 SSM SecureString JSON으로 준비한다. Gateway·서비스 자격을 운영자로 재사용하지 않는다. 비밀값을 명령 인자·Git·로그로 전달하지 않는다.

정식 태그 배포의 `SETTLEMENT_OPERATOR_PARAMETER` 저장소 변수에는 같은 계정·리전의 앱용 `SPRING_APPLICATION_JSON` 파라미터 ARN만 지정한다. `deploy_ecs_apps.py --settlement-operator-parameter ARN`이 이를 Settlement의 SSM 참조로 연결한다. 기본값 `preserve`는 현재 설정을 유지하며, 기존 인라인 JSON이나 다른 파라미터를 덮어쓰지 않는다. 준비와 적용의 같은 ARN을 rollback 기준에 묶고, 이 옵션은 자격 발급·IAM 변경·대조 스케줄 활성화를 수행하지 않는다. 적용 후 `experiment.py --check-only`로 실제 자격과 대조 경로를 다시 확인한다.

승인된 실험실 bootstrap에서 설정 자체가 없으면 `BOOTSTRAP_SETTLEMENT_OPERATOR=true`를 명시할 수 있다. 기본은 `false`다. 기존 배포 역할의 권한으로 rollback 기준 보존 후 암호학적 난수를 SSM SecureString에 만들며, 기존 파라미터는 덮어쓰지 않는다. 값은 CI 출력·배포 기록·Git에 넣지 않는다. IAM 권한을 추가하지 않으며 쓰기 권한이 없으면 ECS 변경 전에 실패한다. 생성된 파라미터는 현재 Task와 정식 금융 대조가 참조하는 운영 설정으로 관리하고, 정상 준비 후 bootstrap 변수를 `false`로 돌려 불필요한 재생성을 요청하지 않는다. 일회 대조를 위해 일일 스케줄 설정을 바꾸지 않는다.
