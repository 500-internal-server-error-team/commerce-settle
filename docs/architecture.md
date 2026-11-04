# 서비스와 코드 경계

구조도는 개발·CI의 서비스 실행·저장·연결 경계를 보여줍니다. 업무 책임은 [업무 설계](design.md#4-서비스-경계와-원천-데이터), 활성화·지원 범위는 본문과 [개발 환경](development.md)이 기준입니다.

[기능 API와 서비스 이벤트](api.md), [서비스별 ERD·컬럼·제약](data-model.md), [업무 정책](policies.md)을 구현 계약으로 사용합니다. ERD는 커머스 계정/주문/쿠폰/알림 뷰·Payment·Settlement·Finance Mock·서비스별 메시지/멱등성/감사 뷰로 나누며 서비스 간 SQL join이나 물리 FK를 추가하지 않습니다.

## 인프라 구조도

[Compose 설정](../compose.yml)을 기반으로 업무 연동을 갖춘 로컬 개발·CI 구성입니다. AWS 배포 구성은 별도 설계 대상입니다. [CI](../.github/workflows/ci.yml)도 같은 Compose로 빈 볼륨 기동·검증·데이터 유지 재기동을 검사하도록 구성돼 있습니다.

### 전체 구성

![통합 인프라 구조도](diagrams/infrastructure.svg)

### 애플리케이션과 인프라

![Spring Boot 애플리케이션과 데이터 인프라](diagrams/runtime.svg)

Spring Boot 영역의 여섯 실행은 우리가 빌드하는 애플리케이션입니다. PostgreSQL·Kafka·Redis는 별도 인프라 컨테이너입니다. Gateway를 제외한 다섯 실행에 DB·Kafka 연결이 설정돼 있으며, Redis 연결 설정은 여섯 실행 모두에 전달됩니다.

`payment-worker`와 `finance-mock`은 Gateway의 공개 라우팅 대상에서 제외합니다. 활성화 플래그는 각 앱 설정을 확인합니다.

각 앱은 HTTP 요청 외에 배치·스케줄러·이벤트 소비·Worker로도 업무를 시작합니다. 아래 [정기 작업과 비동기 실행](#정기-작업과-비동기-실행)이 작업별 실행 책임을 연결합니다. Spring Batch는 해당 앱에 포함하는 라이브러리이며 별도 서버·DB·컨테이너를 뜻하지 않습니다.

DB 접근은 Spring Data JPA를 사용합니다.

PostgreSQL·Kafka·Redis는 각각 `postgres-data`·`kafka-data`·`redis-data` 볼륨을 사용합니다. 모든 컨테이너는 같은 `backend` 네트워크에 연결되며, 이 네트워크를 외부 통신까지 차단하는 방화벽으로 간주하지 않습니다. 호스트에 게시한 포트와 접근 범위는 [네트워크와 관측](#네트워크와-관측)을 따릅니다.

### 사용자 인증과 내부 신원 전달

커머스(`commerce`)가 로그인·세션 발급/폐기와 계정별 인증 가드를 소유하고 Gateway는 [Redis 인증 데이터](data-model.md#redis-인증-데이터)를 읽어 사용자 세션을 검증합니다. 매 보호 요청에서 세션·가드·고정 만료를 확인하므로 커머스에 HTTP 인증 조회를 반복하지 않습니다. 다른 서비스의 PostgreSQL을 Gateway가 읽지 않습니다. 백엔드는 Gateway의 인증 결과와 별도로 역할·주문/상품/정산 소유권을 검사합니다.

외부 `Authorization: Bearer <sessionToken>`은 Gateway에서 검증한 뒤 백엔드로 전달하지 않습니다. 클라이언트가 보낸 `X-Authenticated-Actor-Kind`·`X-Authenticated-Actor-Id`·`X-Authenticated-Session-Hash`를 항상 제거하고, 보호 요청에만 검증한 계정 종류·ID를 넣습니다. 로그아웃 요청에는 현재 세션을 삭제할 수 있도록 검증한 세션의 SHA-256 해시도 전달합니다. 상류 요청의 Authorization은 대상 서비스 전용 Gateway 자격으로 교체합니다. Gateway·서비스·운영자 자격의 형식과 백엔드 판정 순서는 [내부 호출 자격](development.md#내부-호출-자격)을 따릅니다. 백엔드는 이 자격의 발신자가 Gateway이고 해당 공개 경로를 호출할 수 있는지 먼저 검사한 뒤 신원 헤더를 사용합니다. 다른 서비스·운영자 자격에 사용자 신원 헤더 발급 권한을 주지 않습니다. 토큰·자격·세션 해시는 로그·응답에 넣지 않습니다.

가입·로그인·복구·상품/이벤트 조회의 공개 여부는 OpenAPI의 PUBLIC 작업을 HTTP 메서드·정규화한 경로로 대조합니다. 경로 접두사만으로 공개하지 않으며 백엔드의 Gateway 허용 작업도 같은 기준을 사용합니다. 보호 작업에 검증된 계정 종류·ID가 없거나 로그아웃에 세션 해시가 없으면 `401`로 거절합니다. `/internal`·`/mock`는 Gateway 경로를 만들지 않으며 기존 서비스별/운영자별 자격과 허용 경로를 직접 확인합니다. Worker는 접수된 내부 명령·업무 키로 실행하며 사용자 세션을 재사용하지 않습니다. 컨테이너의 같은 네트워크나 클라이언트가 보낸 계정 ID만으로 호출을 신뢰하지 않습니다.

인증용 Redis 쓰기는 커머스만 허용하며 Gateway에는 세션·가드·실행 식별자 읽기와 읽기 전용 검증만 허용합니다. Payment·Settlement·Mock에는 인증 키 접근 권한을 주지 않습니다. 키 접두사가 아닌 [Compose](../compose.yml)의 앱별 ACL로 경계를 적용하며 `default` 사용자는 앱에 전달하지 않습니다. 커머스의 `EVAL`·`EVALSHA`와 금지 명령은 [Compose ACL](../compose.yml), Gateway의 `EVALSHA_RO`·제한된 재적재 권한은 [인증 정책](policies.md#인증공통-api-기준)을 따릅니다. 로그인·폐기의 가드/버전 전이는 [세션 정책](policies.md#폐기와-저장소-복구), 대상별 서비스 자격 설정은 [내부 호출 자격](development.md#내부-호출-자격)을 따릅니다.

Gateway는 `EVALSHA_RO`로 세션을 검증합니다. `NOSCRIPT`이면 고정 검증 스크립트를 `EVAL_RO`로 한 번 실행해 재적재하며 다른 Redis 오류는 503으로 중단합니다. 일반 `EVAL`·`EVALSHA`·`SCRIPT LOAD`와 인증 키 쓰기는 허용하지 않습니다.

### 거래 알림

![커머스의 거래 알림과 모의 발송](diagrams/notifications.svg)

알림의 응용 처리·발송·수신 어댑터는 Commerce 앱 안에 둡니다. 기본 모의 발송과 명시적 본인 시연 채널을 구분하며 새 컨테이너·알림 DB는 만들지 않습니다. 모의 수신 원결과는 별도 트랜잭션으로 저장해 응답 유실·재시작을 재현하고, 설정 변경으로 기존 결과를 바꾸지 않습니다. [실행·장애 설정](development.md#모의-거래-알림)을 따릅니다.

Payment·Settlement는 확정 상태와 알림 요청 Outbox를 함께 저장하고 기존 Kafka 생산자 토픽을 사용합니다. `commerce-notifications.v1` 소비자가 자기 Inbox·알림 항목을 저장한 뒤 ack합니다. 커머스 로컬 전이는 같은 DB에 알림을 원자 저장합니다. 발송은 별도 작업이며 알림 업체 장애로 주문·환불·지급을 롤백하지 않습니다. [정책](policies.md#거래-알림)·[API](api.md#거래-알림-사용-순서)·[ERD](data-model.md#거래-알림)가 상세 계약입니다.

### PG 대역 3종 설계

`finance-mock`은 논리 PG 3종·간편결제 1종과 지급 기관 대역으로 나눕니다. 같은 Spring Boot 앱의 거래 영역이며 Compose 컨테이너를 늘리지 않습니다. 신용카드·체크카드는 PG A/B/C 중 한 곳을, 간편결제는 `EASY_PAY`만 호출합니다. [PG 시험 설정](design.md#pg-3종과-장애-시나리오)과 [간편결제 대역](design.md#간편결제-대역)을 따릅니다.

![금융 모의 서버의 PG·간편결제·지급 API](diagrams/payment-providers.svg)

외부 수단·대역은 결제 시도에 고정하며 미확정 조회·환불도 원거래로 보냅니다. 마일리지 전액 결제는 외부 대역을 호출하지 않습니다. 하나의 Mock 프로세스는 실제 금융기관의 독립적인 인프라 장애 격리를 재현하지 않습니다.

### 관측 구성

![관측과 운영 알람](diagrams/observability.svg)

[Prometheus 설정](../infra/prometheus/prometheus.yml)은 Gateway·Commerce·Payment API·Payment Worker·Settlement·Finance Mock의 `/actuator/prometheus`를 15초마다 수집합니다. Grafana는 [내부 Prometheus 주소](../infra/grafana/provisioning/datasources/prometheus.yml)를 조회합니다.

Prometheus와 데이터 파일시스템 수집기는 신규 접수 판단에 필요하므로 기본 Compose에서 실행합니다. Grafana·Alertmanager·Kafka UI는 `observability`, k6는 `tools` 프로필에서 실행합니다.

Prometheus·Grafana는 각각 `prometheus-data`·`grafana-data` 볼륨을 사용하고, Zipkin 추적은 재시작하면 사라집니다. 관측 도구의 호스트 포트는 [네트워크와 관측](#네트워크와-관측)을 따르며 모두 루프백에 바인딩됩니다. 앱 관리 포트 9091·DB·Kafka·Redis는 호스트에 게시하지 않습니다.

Prometheus가 경보를 평가하고 Alertmanager가 묶음·중복 억제·복구 통지를 처리합니다. 소유 서비스의 DB·Kafka·데이터 볼륨을 별도 수집 경계에서 관측하고 요청·scrape 경로가 DB를 직접 조회하지 않게 합니다. 데이터 볼륨은 읽기 전용이며 Docker 소켓·DB 자격을 사용하지 않습니다. 신호·시효·임계값은 [접수·알람 정책](policies.md#신규-접수-제한과-재개), 연결·검증은 [금융 관측](development.md#금융-관측과-접수-복구)을 따릅니다.

다이어그램의 [SVG와 로고 출처](diagrams/README.md)를 함께 관리합니다.

## 실행 단위

| Gradle 모듈 | Compose 실행 이름 | 역할·소유 DB |
| --- | --- | --- |
| `internal-auth` | 없음 | 내부 호출 자격 판정 라이브러리. 커머스·Payment·Settlement·Finance Mock이 의존하며 DB 없음 |
| `gateway` | `gateway` | 공개 경로 라우팅·Swagger UI, DB 없음 |
| `commerce` | `commerce` | 커머스: 계정/인증·상품·주문·재고·쿠폰·배송지·선착순 이벤트·거래 알림, `commerce` DB·계정 |
| `payment` | `payment-api` | 결제·마일리지·환불 요청/조회 역할, `payment` DB·계정 |
| `payment` | `payment-worker` | PG·간편결제 호출·후속 작업 역할, 같은 `payment` DB·계정 |
| `settlement` | `settlement` | 원장·예약·정산·지급, `settlement` DB·계정 |
| `finance-mock` | `finance-mock` | 외부 PG·간편결제·지급 API 모의 서버, `finance_mock` DB·계정 |

Payment API와 Worker는 같은 jar를 사용하며 `PAYMENT_ROLE`로 역할별 Bean 활성화와 메트릭 태그를 구분합니다.

PostgreSQL 컨테이너 1대에서 표의 네 업무 DB·계정·스키마를 분리합니다. 계정은 자기 DB에만 연결할 수 있고, 다른 서비스 DB에 대한 CONNECT 권한은 없습니다. 애플리케이션에는 자기 DB 자격만 주며, 스키마 생성·변경 절차는 [DB 정책](migrations.md)을 따릅니다. 이 구성은 고가용성이나 독립 운영 DB의 장애 격리를 제공하지 않습니다.

이는 물리 분산·샤딩이 아닌 논리적 소유권 분리입니다. [DB 변경·삭제·FK 정책](migrations.md)을 함께 확인합니다.

Compose DNS로 서비스를 찾으며 별도 서비스 레지스트리는 두지 않습니다.

## 정기 작업과 비동기 실행

![앱 내부 배치·Consumer·지급 Worker의 실행과 저장 경계](diagrams/background-execution.svg)

그룹은 서비스의 소유 범위이며 DB는 별도 PostgreSQL에 있습니다. Scheduler가 Job을 시작하고, 일배치가 저장한 지급 작업은 Worker가 임대합니다. 원장 Consumer는 이 흐름과 독립적으로 이벤트를 반영합니다.

아래는 전체 서비스의 실행 구조입니다. 조건·금액·상태는 연결한 업무 정책이 소유하고, 이 절은 실행 앱·방식과 복구 경계를 소유합니다. 활성화 조건과 검증 명령은 개발 환경 문서를 따릅니다. 정산 배치·지급의 세부 동작은 [정산 정책](policies.md#정산재처리), 자동 정산 대조는 지원하지 않으며 수동 검사 기준은 [정산 대조](policies.md#정산-대조)를 따릅니다.

| 작업 | 실행 앱 | 방식·시작 조건 | 기준 |
| --- | --- | --- | --- |
| 월간 우수고객 선정·등급·쿠폰 발급 | `commerce` | Spring Batch Job 하나에서 대상 확정 → 발급. 월 마감 후 실행하고 미완료 회차 재개 | [월간 혜택](policies.md#발급-대상주기예산), [아래 단계](#월간-혜택-job) |
| 미결제 주문 기한 처리 | `commerce` | 스케줄러가 도래 주문의 취소 절차를 시작. Payment의 승인 차단 확인 전에는 예약 유지 | [주문 기한](policies.md#금액과-주문) |
| 모의 배송완료·자동 구매확정 | `commerce` | 각각 매분 도래 대상을 재조회하고 주문·Outbox를 원자 저장 | [배송·확정](policies.md#배송환불-기한과-자동-구매확정) |
| 선착순 이벤트 종료·미사용 재고 반환 | `commerce` | 스케줄러가 종료 시각을 확인. 신규 참여를 막고 미확정 예약을 제외한 잔여만 반환 | [이벤트 수량](design.md#선착순-상품-이벤트) |
| 쿠폰 만료·할인 약속액 상쇄 | `commerce` | 스케줄러가 만료된 미사용 쿠폰을 처리. 예약·금융 미확정 건은 유지 | [만료 처리](policies.md#쿠폰-만료-처리) |
| 판매 알림 묶음 확정·발송·조회 | `commerce` | 1분 구간 종료 때 묶음 확정, 발송 Worker는 1초마다 도래 작업 임대 | [알림 복구](policies.md#소유권과-복구) |
| 주문 주소 마스킹·알림 보존 처리 | `commerce` | 보존 기한이 지난 종결 거래를 주기 조회. 보존 보류·살아 있는 발송 제외 | [보존 처리](policies.md#보존과-삭제) |
| 외부 승인·환불·미확정 조회 | `payment-worker` | 영속 금융 작업을 임대해 실행·조회·복구. `payment-api`에서는 Worker를 활성화하지 않음 | [금융 Worker](policies.md#금융-worker-임대와-결과-반영) |
| 정산 대상·금액 확정 | `settlement` | 정산 배치가 항목·합계·지급 작업을 저장 | [정산 정책](policies.md#정산재처리), [배치 실행·복구](development.md#정산-일배치-실행과-복구) |
| 지급 실행·보류 재개·미확정 조회 | `settlement` | 저장된 지급 작업을 Worker가 임대·실행 | [지급 복구](policies.md#정산재처리) |
| PG 입금 반영 | `finance-mock` | 매분 도래한 미처리 입금을 원승인당 한 번 반영 | [PG 회수](policies.md#선지급-재원과-pg-회수) |
| Outbox 발행·선행 상태 대기 재처리 | 각 생산·소비 앱 | Commerce Relay는 커밋 후·슬롯 반환·시작 시 깨우고 신호 누락·재시도·만료 임대는 기본 1초 복구 조회로 처리. 소비자는 영속 대기한 원업무를 재판정 | [Kafka 전달·용량 기준](architecture.md#kafka) |
| 신규 접수 제한·재개 판정 | `commerce` | 15초마다 묶어서 관측하고 기존 작업의 처리·복구는 계속 허용 | [접수 제한](policies.md#신규-접수-제한과-재개) |

기본 실행 주기는 미결제 기한·이벤트 종료·쿠폰 만료 스케줄러 매분, 주소·알림 보존 작업 매일 한 번입니다. 실행 시각·묶음 크기·동시성은 서버 설정으로 명시하고 검증합니다. 사용 가능 여부는 요청 시에도 기한과 상태를 검사하므로 스케줄러 지연으로 만료 쿠폰·종료 이벤트를 허용하지 않습니다. 대량 작업은 제한된 묶음과 재시작 지점을 사용합니다.

웰컴 쿠폰은 가입 트랜잭션, 판매자 쿠폰은 발급 요청에서 처리합니다. Redis 세션은 서버의 만료 검사와 TTL, Kafka 토픽은 보존 설정을 사용합니다. 마일리지 적립·만료와 판매자 구독 갱신은 이 실행 계약의 범위에서 제외합니다. Outbox·Inbox·금융 Job·원장의 자동 삭제는 [보존과 삭제](policies.md#보존과-삭제)에 따라 비활성입니다.

### 월간 혜택 Job

`commerce`의 `gradeCouponJob`은 **대상·등급 확정과 자동 검증 → 쿠폰 발급**의 두 Step으로 실행합니다. 업무 회차·대상은 [쿠폰 발급 테이블](data-model.md#coupon_issue_runs)에 보존하며 Batch 실행 이력과 구분합니다. 같은 `targetMonth`·`runId`로 재시작하고 확정한 대상을 재선정하지 않습니다.

선정·교정은 [월간 혜택 정책](policies.md#발급-대상주기예산), 활성화·기한 경과 처리는 [실행 안내](development.md#주문-업무-흐름)를 따릅니다.

### 실행·장애·자원 경계

- 스케줄러는 실행 계기를 제공하고 업무 상태는 DB에 보존합니다. 기동·장애 복구 시 놓친 도래 대상과 미완료 회차를 확인하며, 실행 시각 한 번을 놓쳤다는 이유로 다음 월까지 기다리지 않습니다. 월 마감 자료가 없으면 현재 자료로 대체하지 않습니다.
- 여러 인스턴스가 같은 작업을 조회해도 업무 키·조건부 상태 전이·임대 token으로 실행·결과 반영을 보호합니다. `@Scheduled`나 한 JVM의 잠금만으로 중복 실행이 차단됐다고 보지 않습니다. 예약 중인 쿠폰·미확정 금융 작업은 시간 경과만으로 해제하지 않습니다.
- 실행 위치는 위 앱 안이며 새 컨테이너·공통 업무 DB를 추가하지 않습니다. API·Consumer·배치·Worker의 실행 풀과 동시성 상한을 정하고, 공유 JDBC 풀의 대기·API 지연을 함께 측정합니다. 앱 내부 풀 구분이 프로세스 장애 격리를 제공하지는 않으며 분리는 실제 경합·운영 필요로 판단합니다.
- 운영 지표는 작업·역할별 마지막 성공 회차/시각, 실행 시간·처리 건수, 실패·미완료 건수와 도래 후 최장 대기를 포함합니다. 월간 실행 누락·마감 자료 부재도 탐지하고 정상 미래 대기와 보존 보류는 적체에서 제외합니다. 계정·주문 ID는 label로 사용하지 않으며 알람 기준은 [운영 알람](policies.md#운영-알람)에 연결합니다.

## 4계층

각 서비스가 자기 업무의 진행·완료·보상을 소유하며 [핵심 업무 흐름](design.md#6-핵심-업무-흐름)으로 연결합니다.

업무 모듈의 루트 패키지는 `com.commercesettle.<commerce|payment|settlement|financemock>`입니다.

| 패키지 | 책임·의존 방향 |
| --- | --- |
| `presentation` | Controller·입출력 DTO·오류 변환. `application` 호출, DB·Kafka 어댑터 직접 참조 금지 |
| `application` | 유즈케이스·트랜잭션·SAGA 조정. `domain`과 포트 사용, presentation·infrastructure 의존 금지 |
| `domain` | 상태 전이·금액 불변식·엔티티·저장소 계약. HTTP·응용·인프라·Spring 의존 금지. JPA 매핑 주석 허용 |
| `infrastructure` | DB·Kafka·PG 어댑터·설정. domain·application의 포트 구현, presentation 의존 금지 |

ArchUnit이 계층 경계와 다른 서비스 구현의 직접 import를 검사합니다. 업무 정합성은 도메인 테스트·통합 시나리오로 검증합니다.

### DDD 집계와 검증

업무 도메인은 규칙·모델·변경 책임의 구분입니다. 아래 책임을 기존 서비스 안에서 나누며, 도메인 수에 맞춰 배포 서비스나 Gradle 모듈을 늘리지 않습니다.

| 소유 서비스 | 업무 도메인 | 최종 판단·변경 책임 |
| --- | --- | --- |
| 커머스 | 계정·인증 | 구매자·판매자 신원, 계정 상태·세션·복구 토큰. 각 기능은 인증된 주체와 자기 리소스 소유권을 대조 |
| 커머스 | 판매자 | 판매 상태·판매자 정보·수취 계좌와 버전. 지급 컨텍스트의 판매자 정보 원천 |
| 커머스 | 상품·재고 | 상품 가격·판매 조건, ProductInventory의 가용·예약 수량과 재고 복원 |
| 커머스 | 주문·이행 | Order의 가격·할인·결제 구성·배송지 사본, 배송·취소/반품 자격·자동 구매확정 |
| 커머스 | 쿠폰 | 세 종류의 발급 자격·할인 조건·비용 부담자, BuyerCoupon의 예약·사용·복구, coupon_history의 할인 비용 이력 |
| 커머스 | 선착순 이벤트 | SalesEvent의 행사 가격·배정 수량·참여 한도·확정 판매량·매진 |
| 커머스 | 거래 알림 | NotificationDelivery의 수신자·묶음·발송 상태. 원천 도메인이 확정한 사실을 안내하며 금융 상태를 변경하지 않음 |
| Payment | 결제 | Payment의 승인 회차·외부 시도·결제 결과, 확정 주문의 B와 적용 정책에 따른 수수료·판매자 순액 확정 |
| Payment | 마일리지 | MileageWallet의 가용 잔액·예약·차감·복원과 mileage_entries. 결제·환불 상태와 같은 로컬 트랜잭션으로 기록 |
| Payment | 금융 환불 | FinancialRefund의 원구성 환불, 응용 서비스의 SAGA 조율. 원장 상쇄·주문 복구 확인까지 전체 진행을 추적 |
| Settlement | 정산 원장 | settlement_entries의 금액 배분·업무 키·원거래 상쇄. SettlementItem과 함께 최초 금액 보존과 중복 반영 방지 |
| Settlement | 정산 | SettlementItem의 지급 가능 조건·환불/지급 단일 선점, SettlementBatch의 대상·합계 고정 |
| Settlement | 지급 | Payout의 수취/출금 계좌 사본·업무 키·외부 실행·조회·성공 결과 |
| Finance Mock | 모의 금융 거래·현금 | 대역의 승인·환불·지급·잔액과 mock_cash_entries. 모의 현금의 실제 증감을 자기 DB에 기록 |

같은 서비스의 여러 집계가 주문 예약처럼 한 원자 처리를 요구하면 응용 서비스가 같은 DB 트랜잭션으로 조율합니다. 주문 응용 서비스는 재고·쿠폰·이벤트의 각 규칙을 호출해 함께 저장하고, 해당 상태를 임의로 덮어쓰지 않습니다. 서비스 간 처리는 이벤트·SAGA를 사용합니다. Settlement는 Payment가 확정한 수수료·순액을 검증·기록·상쇄하며 현재 정책으로 과거 거래를 재계산하지 않습니다.

다른 집계는 ID로 참조하고 다른 서비스 엔티티를 가져오지 않습니다. 불변 금액·정책 사본은 값 객체, 상태 전이는 루트의 동작으로 표현합니다. 모든 테이블을 집계 루트로 만들거나 모든 엔티티마다 Repository를 만들지는 않습니다. Repository는 필요한 루트의 저장 경계를 제공합니다. 검증 책임은 [요청·응용·도메인·DB 경계](../CONTRIBUTING.md#검증-경계)를 따릅니다.

### 시간 정보와 감사 기록

가변 엔티티의 생성 시각은 고정하고 수정 시각은 성공한 상태 변경에만 갱신합니다. 업무 시계의 `Instant`를 마이크로초로 맞춰 저장하고 [공통 시간 규약](../CONTRIBUTING.md#요청과-응답)을 따르며 클라이언트·프레임워크가 상태 변경을 우회하지 않습니다. 서비스 안에서 공통 매핑을 재사용하되 추가 전용 원장·이벤트·감사 행에는 수정 가능한 부모를 강제하지 않습니다.

주요 상태 전이·운영 재처리는 인증된 실행 주체와 업무 변경을 서비스별 감사 기록에 함께 커밋합니다. 신뢰한 발신자를 기록하고 원사용자를 추정하지 않습니다. 시간 정보·진단 로그는 행위 감사나 금융 원장을 대신하지 않으며 필드·보존은 [감사 테이블](data-model.md#audit_logs)이 기준입니다.

## 서비스 내부 분류

루트는 위 4계층으로 나누고 그 안에서 아래 역할을 사용합니다. 업무가 늘면 `application/service/order`처럼 역할 아래에 도메인 패키지를 둡니다. 빈 패키지·인터페이스·기반 클래스를 미리 만들거나 서비스마다 기능별 루트와 계층별 루트를 혼용하지 않습니다.

| 위치 | 책임 |
| --- | --- |
| `presentation/controller` | HTTP 요청 진입점 |
| `presentation/dto` | 외부 요청·응답 DTO |
| `presentation/exception` | MVC 예외→ProblemDetail 변환 |
| `application/service` | 유즈케이스·SAGA 조율 |
| `application/dto` | HTTP·메시징과 독립된 입력·결과가 필요할 때 |
| `application/port` | PG·캐시·이벤트 발행 등 외부 기능 계약 |
| `application/event` | 서비스 간 이벤트 envelope·payload. 송수신 서비스가 자기 계약 타입 관리 |
| `domain/model` | 엔티티·값 객체·상태 |
| `domain/repository` | 업무 저장소 인터페이스. Spring Data 상속 금지 |
| `domain/service` | 한 엔티티에 둘 수 없는 순수 업무 규칙·정책 |
| `domain/exception` | 업무 규칙 위반. HTTP 상태·ProblemDetail·응답 메시지와 분리 |
| `infrastructure/persistence` | JPA 어댑터·Spring Data 인터페이스·기술용 Outbox/Inbox 저장 |
| `infrastructure/messaging` | Kafka Producer·Listener, Outbox 발행기·재처리 스케줄러 |
| `infrastructure/client` | 외부 HTTP 구현·클라이언트 전용 요청/응답 |
| `infrastructure/cache` | Redis 세션·인증 가드·캐시·잠금 어댑터 |
| `infrastructure/config` | Spring 설정·클라이언트 구성·OpenAPI |
| `infrastructure/observability` | 자기 저장소·Kafka의 읽기 전용 관측과 불변 메트릭 snapshot |

**엔티티는 `domain/model`에 둡니다.** 단순 CRUD까지 별도 JPA 엔티티와 도메인 모델로 이중화하지 않고 JPA 매핑을 함께 사용합니다. 엔티티의 상태 변경은 의미 있는 메서드로 제한하고 public setter·HTTP DTO·직렬화 책임을 넣지 않습니다. 식별자 참조와 DB 제약은 [FK·데이터 정책](migrations.md#데이터-보존과-제약-조건)을 따릅니다. 복잡한 영속 구조 때문에 분리가 필요해지면 이유·변환 비용과 함께 검토합니다.

트랜잭션 경계는 응용 서비스에서 시작합니다. 도메인에는 `@Service`·`@Transactional`·Spring Data·Kafka·Redis 호출을 넣지 않습니다. 순수 도메인 정책의 Bean 등록이 필요하면 인프라 설정에서 구성합니다. 포트의 입력·출력도 외부 클라이언트·JPA 구현 타입에 의존하지 않습니다.

인증의 Redis·비밀번호 해시·복구 토큰 전달도 응용 포트 뒤의 인프라 어댑터로 둡니다. Gateway의 세션 필터는 WebFlux의 비동기 Redis 접근을 사용하며 JPA·비밀번호 검증을 수행하지 않습니다.

Kafka Listener는 메시지 검증·응용 서비스 호출·처리 결과에 따른 ack를 맡습니다. 금액·상태 전이 규칙은 도메인, 흐름·원자 저장 경계는 응용 서비스가 맡습니다. Outbox·Inbox의 기술용 레코드는 업무 엔티티와 구분합니다. 생산자는 업무 상태와 Outbox, 소비자는 Inbox와 실행 작업을 각각 같은 DB 트랜잭션에 저장합니다.

유즈케이스에 구현체 하나뿐이면 인터페이스와 `Impl`을 쌍으로 만들지 않습니다. 생성자 주입을 사용합니다. 요청 DTO의 값을 응용 서비스 인자로 전달할 수 있으면 단순 중계를 위한 Command를 추가하지 않습니다. 응용 계층이 HTTP DTO를 참조하거나 외부 응답에 엔티티를 직접 사용하지 않습니다.

Payment API·Worker는 패키지를 복제하지 않습니다. HTTP와 비동기 실행 진입점을 구분하고 역할 전용 Bean을 추가할 때 `PAYMENT_ROLE`에 맞는 활성화 조건을 함께 설정·검증합니다. Gateway는 WebFlux 라우팅·필터 경계이므로 업무용 4계층을 억지로 만들지 않고 `config`, `filter`, `exception` 등 필요한 역할만 둡니다. 블로킹 JPA 호출·금융 업무 로직을 넣지 않습니다.

### 저장소 파일 위치

업무 모듈은 도메인 이름, 외부 대역은 대상·역할로 이름을 짓고 `-service`는 생략합니다.

애플리케이션 모듈은 위 [실행 단위](#실행-단위), 문서·테스트는 [문서 구조](../CONTRIBUTING.md#문서-구조와-기준-위치)와 [테스트 작성](../CONTRIBUTING.md#테스트-작성)을 따릅니다.

| 위치 | 책임 |
| --- | --- |
| `<모듈>/src/main/resources` | 앱 설정 |
| `infra/` | Compose가 사용하는 DB 초기화·관측 설정. 서비스 DDL은 `infra/postgres/schema` |
| `<모듈>/…/infrastructure/` | Java 어댑터. 루트 실행 환경 설정과 구분 |
| `config/`, `scripts/`, `tests/load/` | 개발 도구 설정, 보조 명령, k6 부하 시나리오 |
| `internal-auth/` | 내부 호출 자격 판정의 공유 라이브러리. 업무 엔티티·DTO·상태는 공유하지 않음 |

새 파일은 가장 가까운 기존 책임에 두고, 실제 역할이 늘 때 패키지를 추가합니다.

### 본인 카카오톡 시연 어댑터

Commerce의 `infrastructure/client/kakao`가 공식 self-send HTTP와 본인 토큰 파일의 갱신을 맡습니다. 별도 알림 서비스·금융 진행 상태를 만들지 않습니다. 최초 동의는 Git 밖 비밀 파일을 생성하는 loopback 로그인 도구로 받고 Gateway에 인증 콜백이나 토큰 조회 API를 공개하지 않습니다. 기본 앱에는 실제 발송 클라이언트가 없으며 명시적 시연 설정과 시험 계정 목록을 사용합니다. [시연 설정](development.md#본인-카카오톡-시연)을 따릅니다.

## Kafka

로컬 Kafka는 단일 브로커이며 토픽 자동 생성을 끕니다. Commerce·Payment·Settlement가 생산자별 원본 3개와 소비자별 DLT 4개 토픽을 준비합니다. smoke는 실제 7개 토픽의 파티션·복제·보존 설정을 확인하고, 별도 임시 토픽에서 메시지를 왕복시킨 뒤 삭제합니다.

생산자별 토픽·책임별 Consumer 그룹, 업무 상태·Outbox의 원자 저장과 Inbox·처리 대기 작업의 커밋 후 offset 승인을 구현 기준으로 삼고 파티션·동시 실행·보존은 [실행 설정](development.md#kafka-전달-기반), 접수 제한은 [업무 정책](policies.md#신규-접수-제한과-재개)을 따릅니다. 이벤트 종류·key·그룹·payload는 [API 이벤트 계약](api.md#이벤트-연동)이 소유합니다. 토픽·서비스 사이의 전역 순서는 가정하지 않으며 금융 이벤트에 배송지·비밀번호·인증 토큰을 넣지 않습니다.

Outbox·Inbox·원문 격리·DLT 작업의 저장 관계는 [메시지 데이터 모델](data-model.md#메시지멱등성감사), 활성화와 복구 절차는 [Kafka 전달 기반](development.md#kafka-전달-기반)을 따릅니다. Kafka 응답은 DB 트랜잭션 밖에서 기다립니다.

### 용량과 장애 후 배출

정상 성공 결제 1건은 Commerce의 `OrderPaymentRequested` 1개와 Payment의 접수·확정·알림 3개 이벤트를 만듭니다. 결제 확정 1,000 TPS는 초기 흐름 약 4,000메시지/초이며 구매확정·정산·환불·취소·재발행은 별도입니다. 여러 그룹의 구독은 발행량 대신 소비·전송·DB 비용을 늘립니다. 평균 1kB 가정의 15분 payload는 약 3.6GB이며 실측 디스크 요구량과 구분합니다.

파티션 수는 `ceil(토픽 유입량 / (파티션당 측정 처리량 × 목표 사용률))`과 메시지 크기·key 편향으로 산정합니다. 일반 그룹의 소비 병렬도는 파티션 수가 상한이며 DB·Worker 능력은 별도입니다. 증설 시 key 재배치와 순서 안전성을 검증하고 DB 잠금·연결·외부 호출 병목을 먼저 확인합니다.

중단 D초, 유입 λ건/초이면 적체 B=λD이며 T초 내 배출에는 `μ≥λ+B/T`의 처리 능력이 필요합니다. 예를 들어 1,000건/초를 5분 멈추고 유입을 유지하며 5분 안에 배출하려면 2,000건/초 이상이 필요합니다. 재시도·편향·여유 용량은 추가합니다. lag가 0이어도 DB 작업이 남으면 정합 복구 완료가 아닙니다. 시험 전 원본·복제·인덱스·DB/WAL·기존 점유를 합산하고 저장 공간 30% 이상 여유를 확보합니다. 로컬 단일 브로커의 ACK나 같은 호스트 컨테이너 증설은 호스트 장애 내성을 제공하지 않습니다.

## 네트워크와 관측

Gateway는 빌드에 포함한 [OpenAPI](openapi.json)의 `/api/` 작업을 HTTP 메서드·경로와 `x-owner-service`로 대조해 커머스·Payment API·Settlement로 전달합니다. 경로 접두사가 같은 판매자 API도 상품·계정은 커머스, 정산·지급 조회는 Settlement로 구분합니다. 명세에 없는 경로·메서드와 `/internal`·`/mock`·관리 경로는 전달하지 않습니다. API 명세는 `/v3/api-docs/commerce|payment|settlement`, Swagger UI는 `/swagger-ui.html`입니다.

백엔드는 컨트롤러 전에 대상별 호출 자격과 허용 발신자를 검사하고, 사용자 작업에는 계정 종류·거래 소유권도 대조합니다. MVC 앱의 자격 판정은 `internal-auth`를 사용하며 설정·교체는 [내부 호출 자격](development.md#내부-호출-자격)을 따릅니다. 내부 HTTP는 평문이므로 로컬 개발 환경에서만 사용합니다. Gateway 자체 오류는 [공통 오류 계약](../CONTRIBUTING.md#오류)으로 응답하고 백엔드 오류는 보존합니다. 전송 계층의 자동 재시도를 끄며 연결 단절을 금융 실패로 확정하지 않습니다.

`v3`는 Springdoc의 OpenAPI 3 문서 경로이며 서비스 API의 세 번째 버전을 뜻하지 않습니다.

서비스 관리 포트 9091은 컨테이너 내부 전용입니다. Gateway 업무 포트와 분리해 `/actuator/**`·`finance-mock`·내부 API를 외부 경로에 연결하지 않습니다. 기본 호스트 포트는 Gateway `127.0.0.1:8080`, Zipkin `127.0.0.1:9411`, Prometheus `127.0.0.1:9090`입니다. 선택 `observability` 프로필은 Alertmanager·Grafana·Kafka-UI 포트를 루프백에 추가합니다. 데이터 파일시스템 수집기는 호스트 포트를 게시하지 않습니다.

수집 대상과 전달 경계는 위 [관측 구성](#관측-구성)을 따릅니다. 금융 지표·접수 복구는 [금융 관측](development.md#금융-관측과-접수-복구), 격리 지표·조치는 [DLT 관측](development.md#dlt-관측과-조치)에서 확인합니다. 승인 완료 TPS와 정산 전체 지연은 별도 업무 시나리오로 계측합니다.

### 로그 수집

KST 시각·`+09:00`이 있는 콘솔 로그를 `docker compose logs`로 확인합니다. JSON 출력·중앙 로그 수집은 지원하지 않습니다. 기록 항목·민감값 처리는 [공통 로그 계약](../CONTRIBUTING.md#로그)을 따릅니다.

## Redis·Zipkin·k6

Redis는 내부 네트워크에서 앱별 ACL 사용자로 연결합니다. AOF는 `redis-data` 볼륨에 보존합니다. 결제·정산의 기준 데이터를 Redis에 저장한다는 뜻은 아닙니다. 키 접두사 자체는 보안 격리가 아니며, 앱이 접근할 수 있는 키는 [위 ACL](#사용자-인증과-내부-신원-전달)이 제한합니다. 키는 `commerce-settle:<domain>:<purpose>:<id>`로 구분하고 TTL·무효화·장애 시 동작은 해당 도메인 담당자가 설계합니다. 금액·멱등성의 최종 정합성을 캐시만으로 보장하지 않습니다. 128MB·noeviction 설정은 메모리 초과 쓰기를 거절하므로 사용량을 관측해야 합니다.

로그인 세션은 Redis를 원본으로 사용하며 [세션 정책](policies.md#인증공통-api-기준)을 따릅니다. 고정 만료는 expiresAt과 TTL을 함께 검사하고 메모리 초과·인증 가드 누락·저장소 오류를 우회하지 않습니다. 세션 세대와 승인한 Redis 실행 식별자는 Gateway·커머스에 동일하게 배포할 인증 설정입니다. 세션 세대는 `AUTH_SESSION_EPOCH`, 실행 식별자는 `AUTH_REDIS_RUN_ID`로 Gateway·커머스에 적용합니다. 두 서비스는 연결한 Redis의 run_id가 승인값과 같을 때만 사용자 인증 게이트를 열고, 연 뒤 연결이 끊기거나 run_id가 다르면 재기동 전까지 닫습니다. Redis 재접속 뒤에는 AOF 복원만으로 인증을 재개하지 않고 [복구 절차](development.md#redis-세션-검증과-복구)를 거칩니다.

Zipkin은 Micrometer·OpenTelemetry의 HTTP 추적을 수집합니다. 샘플링 기본값은 10%이며 환경 변수로 조정합니다. smoke는 sampled `traceparent`로 Gateway CLIENT→Commerce SERVER의 부모 관계를 확인합니다. Kafka 추적의 전달·재시도 경계는 [Kafka 전달 기반](development.md#kafka-전달-기반)을 따릅니다. 개발용 메모리 저장소는 최대 10,000개 span을 보관하며 재시작하면 추적이 사라집니다.

k6는 `tools` 프로필에서 명령으로 실행합니다. 기본 스크립트는 Gateway의 Commerce 명세 조회만 검증합니다. 결제 완료 TPS는 별도 업무 시나리오로 측정합니다. 부하 발생기를 서버와 같은 호스트에서 실행하면 자원을 공유하므로 측정 환경에 명시해야 합니다.
