# DB 스키마와 데이터 정책

업무 설계와 [데이터 모델](data-model.md)은 테이블·컬럼·제약·인덱스·데이터 보존 및 검증 조건을 정의합니다. 스키마를 생성·변경하는 도구와 적용 절차는 이 문서가, 현재 환경의 실행 명령은 [개발 환경](development.md)이 소유합니다. 실행 도구가 바뀌어도 업무 설계의 용어를 일괄 치환하지 않습니다. 실제 스키마·불변식이 바뀔 때 관련 설계를 갱신합니다.

새 DB에 전체 스키마를 생성하는 일과, 데이터가 있는 DB의 구조를 바꾸는 일은 적용 대상과 복구 방법이 다릅니다. 아래 DDL은 현재 구현된 스키마의 생성 정의이며, 데이터 모델의 모든 테이블이 구현됐다는 뜻은 아닙니다.

| DB | 현재 스키마 DDL |
| --- | --- |
| commerce | [commerce.sql](../infra/postgres/schema/commerce.sql) |
| payment | [payment.sql](../infra/postgres/schema/payment.sql) |
| settlement | [settlement.sql](../infra/postgres/schema/settlement.sql) |
| finance_mock | [finance_mock.sql](../infra/postgres/schema/finance_mock.sql) |

## 현재 개발 환경의 적용 방식

배포 전 개발 단계에서는 **서비스 DB마다 초기 SQL 한 파일**을 관리하고 Flyway·증분 migration을 사용하지 않습니다.

1. 새 테이블·컬럼·제약·인덱스는 위 파일의 생성 정의에 반영합니다. 운영 데이터에 적용할 ALTER·데이터 이관 SQL을 개발 변경마다 추가하지 않습니다.
2. PostgreSQL의 빈 데이터 볼륨 초기화 때 [01-init.sh](../infra/postgres/01-init.sh)가 DB·계정·권한을 만든 뒤 각 서비스 역할로 자기 DDL을 한 트랜잭션에서 실행합니다. 테이블·인덱스는 서비스 계정이 소유하며 다른 서비스 DB를 참조하지 않습니다.
3. 앱은 DDL을 실행하지 않고 `spring.sql.init.mode=never`·`ddl-auto=validate`를 유지합니다. Payment API·Worker가 동시에 기동해도 DDL을 중복 실행하지 않습니다. 초기화 실패를 무시하거나 Hibernate `update`·`create`로 보완하지 않습니다.
4. DB 통합 시험은 같은 파일을 테스트 리소스로 복사해 빈 전용 PostgreSQL에 적용합니다. Compose의 빈 볼륨 기동과 기존 데이터를 유지한 재기동도 확인합니다.

**기존 볼륨에서는 초기 SQL이 다시 실행되지 않습니다.** 파일 수정·이미지 재빌드·앱 재시작만으로 기존 DB 구조를 바꾸지 않습니다. 구조를 바꿨으면 새 빈 개발 DB에서 검증하며, 기존 DB를 지우는 작업은 보존할 데이터가 없고 사용자가 삭제를 명시한 경우에만 실행합니다. 운영 또는 공유 데이터에 전체 생성 SQL을 재실행하지 않습니다.

## 최초 배포와 기존 데이터 변경

초기 SQL은 빈 DB 생성용입니다. 기존 데이터 변경은 아래처럼 대상·호환성·검증·복구를 정한 별도 절차로만 적용합니다.

### 기존 DB의 추적 컬럼 추가

Commerce·Payment·Settlement의 Outbox·Inbox, Payment의 payment_jobs, Settlement의 payout_jobs에 nullable `trace_parent varchar(55)`를 추가합니다. Payment Inbox에는 nullable `observation_state varchar(16)`도 추가합니다. [commerce-tracing.sql](../infra/postgres/upgrade/commerce-tracing.sql)·[payment-tracing.sql](../infra/postgres/upgrade/payment-tracing.sql)·[settlement-tracing.sql](../infra/postgres/upgrade/settlement-tracing.sql)은 기존 데이터·원문·해시·금액·offset을 갱신하지 않습니다. 기존 부모를 소급 생성하지 않습니다.

1. 대상 DB·소유 역할과 현재 앱 이미지·스키마를 확인하고 기존 복구 기준을 보존합니다. 전체 초기 SQL을 재실행하지 않습니다.
2. 새 앱 전에 각 소유 DB에서 해당 파일을 `psql --set ON_ERROR_STOP=1 --file ...`로 한 번씩 적용합니다. 앱은 계속 `ddl-auto=validate`·`spring.sql.init.mode=never`를 사용합니다.
3. 스크립트는 DB마다 한 트랜잭션, 잠금 대기 2초·실행 15초 상한입니다. 잠금 시간 초과나 기존 동명 컬럼의 타입·길이·nullable 불일치면 해당 DB를 롤백하고 새 앱 적용을 중단합니다. DB 간 원자 적용은 아니므로 어느 DB가 완료됐는지 따로 확인합니다. 상한을 끄거나 컬럼을 삭제해 강행하지 않습니다.
4. 모든 대상 컬럼의 타입·길이·nullable과 기존 행·업무 상태·원문 보존을 확인한 후 Commerce·Payment API/Worker·Settlement·Finance Mock 이미지를 교체하고 health·새 metric·선택 거래의 trace 부모 연결을 확인합니다. 신규 빈 DB는 기존 schema 파일로 같은 구조를 만듭니다.
5. 앱 복구가 필요하면 기록한 이전 이미지를 적용합니다. 추가 nullable 컬럼은 남겨 구 이미지가 무시하도록 하며 기존 원문·데이터를 삭제하거나 컬럼 DROP으로 되돌리지 않습니다. 부분 완료 뒤 재실행은 `ADD COLUMN IF NOT EXISTS`와 호환성 검사로 처리합니다.

`DurableTraceIntegrationTest`는 실제 PostgreSQL에서 저장·발행한 행을 가진 기존 구조를 재현하고 업그레이드를 두 번 적용해 관측 필드를 제외한 행 bytes/값 보존과 nullable 추가를 확인합니다. 실제 배포 환경의 소유 역할·실행 결과는 배포 때 별도로 확인합니다. 이 절은 다른 스키마 변경의 자동 업그레이드 체계를 추가하지 않습니다.

## 데이터 보존과 제약 조건

- 구매자·판매자 계정과 상품처럼 삭제가 필요한 업무 데이터는 `deleted_at`으로 논리삭제하고 일반 조회에서 제외합니다. 삭제 상태의 접근·재등록·유니크 기준은 해당 테이블 명세에 적습니다. 구매자·판매자 계정의 로그인 식별자 유일성은 계정 종류별로 정하고 같은 이메일이라는 이유로 계정을 합치지 않습니다.
- 구매자 주소록과 주문 당시 배송지 사본은 구분합니다. 주소록 수정이 기존 주문을 덮어쓰지 않으며, 배송지의 접근·보존·삭제 기준을 명세에 적고 금융 원장 보존을 이유로 주소를 무기한 보존하지 않습니다.
- 결제·환불·정산 이력은 삭제하지 않고 상태 전이와 추가 기록으로 보존합니다. 확정 원장의 금액은 덮어쓰지 않으며 정정·상쇄 항목을 추가합니다.
- 서비스 간·같은 서비스 내부 모두 FK constraint를 두지 않습니다. 참조 유효성은 애플리케이션에서 검증하며, `NOT NULL`·`UNIQUE`·`CHECK`와 조회 인덱스는 필요한 불변식에 맞게 둡니다. FK를 생략해도 중복 방지·금액 제약은 유지합니다. 내부 참조 대조·재검토 조건은 [내부 참조 검증](data-model.md#내부-참조-대조)을 따릅니다.
- 시각 컬럼은 `timestamptz`를 사용합니다. 삭제·보존 정책을 바꾸거나 데이터를 물리적으로 제거할 때는 접근 영향·보존 범위·복구 절차를 PR에서 검토합니다.

## 기존 DB의 업무 지표 관측 컬럼

[Commerce](../infra/postgres/upgrade/commerce-business-metrics.sql)·[Payment](../infra/postgres/upgrade/payment-business-metrics.sql)·[Settlement](../infra/postgres/upgrade/settlement-business-metrics.sql) 소유 DB에서 새 앱 배포 전에 `psql --set ON_ERROR_STOP=1 --file ...`로 적용합니다. 기존 데이터가 있는 DB에 초기 schema 전체를 재실행하지 않습니다. Commerce Outbox·Payment Inbox/Outbox·Settlement Inbox의 nullable `order_accepted_at timestamptz`와 기간 집계/주문-cycle 조회 인덱스만 추가하며, 기존 원문·해시·금액·상태·offset은 변경하지 않습니다. 과거 접수 시각은 소급 생성하지 않습니다.

스크립트는 DB별 한 트랜잭션이며 잠금 2초·실행 15초 상한, 기존 동명 컬럼의 타입·nullable 검사를 유지합니다. 상한 내 인덱스를 만들 수 없거나 동명 인덱스 정의가 다르면 배포를 중단하고 해당 DB의 실제 정의와 계획을 확인합니다. 인덱스 이름만으로 적용 완료를 판단하지 않습니다. 적용 전후 컬럼·인덱스 정의, 기존 행/원문 보존, 선택 거래의 시각 전달과 실제 metric을 검증합니다. 세 DB의 적용 결과를 따로 기록합니다. 이전 이미지로 복구할 때 추가 nullable 컬럼·인덱스는 남기며 DROP으로 되돌리지 않습니다.

## 기존 DB의 결제 회차별 예약

[payment-retry-cycles.sql](../infra/postgres/upgrade/payment-retry-cycles.sql)은 기존 단일 Payment 예약 제약을 `(payment_id,cycle_id)` 유일 제약과 결제별 RESERVED 부분 유일 인덱스로 바꿉니다. Payment 소유 역할로 `psql --set ON_ERROR_STOP=1 --file ...`을 실행하며 한 트랜잭션·잠금 2초·실행 15초 상한입니다. 동명 제약·인덱스 정의가 다르거나 기존 행이 새 제약을 위반하면 전체를 롤백하고 배포를 중단합니다. 금융 행·원장·원문을 수정하거나 삭제하지 않습니다.

1. DB·이미지·Task Definition과 기존 행/제약을 기록합니다. Worker의 기존 `APP_PAYMENT_REQUEST_PROCESSOR_ENABLED=false` 설정으로 새 회차 접수를 멈추고 실행 중 처리가 끝났는지 확인한 뒤 업그레이드를 적용합니다.
2. 회차별 예약 조회·승인 fencing을 포함한 새 Payment API/Worker를 배포합니다. Worker의 접수 처리 중단을 유지한 채 모든 서비스 health를 확인하고, 이때의 새 이미지·Task Definition을 복구 대상으로 보존합니다. 기존 승인·환불 작업 처리기는 새 회차를 구분하는 코드로 유지합니다.
3. Worker 접수 설정을 원래 true로 복구해 활성화합니다. 실제 거절→재시도→승인→정산과 혼합 결제 환불을 확인합니다. 업그레이드 반복 적용 시 금융 행이 보존되는지 별도 PostgreSQL 시험으로 확인합니다.
4. 새 회차 수용 후 문제가 생기면 Worker만 2단계의 새 이미지·접수 중단 Task Definition으로 되돌립니다. 회차별 조회 코드·확장 제약·금융 이력은 유지하고 전진 수정합니다. 구 단일 예약 조회 이미지로 일괄 역배포하거나 과거 예약 행을 지우지 않습니다. 구 이미지 복구는 접수·처리를 멈춘 상태에서 **새 회차 기록이 전혀 없음을 입증한 활성화 이전**에만 판단합니다.

새 회차 유무는 여러 회차의 `PaymentAccepted`, 승인 시도·예약 이력, 현재 회차와 최초 요청 회차 차이를 함께 검사합니다. 카드 결제는 예약 행이 없으므로 예약 개수만으로 구 이미지 복구 가능 여부를 판단하지 않습니다.
