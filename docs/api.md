# 기능 API

기계 판독 기준은 [OpenAPI 3.1.1 명세](openapi.json)입니다. 요청/응답 필드·필수값·타입·오류·권한·멱등 헤더를 포함합니다. 이 문서는 API를 찾는 방법과 서비스 간 사용 순서를 설명합니다. 선택 기능의 활성화·연결 조건은 [개발 환경](development.md)을 따릅니다. [업무 정책](policies.md)과 [ERD](data-model.md)를 함께 적용합니다.

API 이름·HTTP 메서드·명세 변경은 [API 경로와 명세 규칙](../CONTRIBUTING.md#api-경로와-명세)을 따릅니다.

## 공통 계약

- 필드·권한·상태·오류·멱등키·필수 헤더는 OpenAPI의 해당 작업이 기준입니다. 시간·페이지·오류 형식은 [공통 요청 규약](../CONTRIBUTING.md#요청과-응답)을 따릅니다.
- `x-actor`는 호출 권한, `x-owner-service`는 처리 앱입니다. Gateway는 메서드·경로별로 전달하고 `/internal`·`/mock`를 공개하지 않습니다. 세션·신원 전달은 [인증 경계](architecture.md#사용자-인증과-내부-신원-전달)를 따릅니다.
- 접수와 금융 완료는 다릅니다. 응답이 유실되면 원업무 키·조회 경로로 결과를 확인하며 새 키로 성공을 추측하지 않습니다. 세션·키 폐기와 저장소 장애 응답은 [인증 정책](policies.md#인증공통-api-기준)을 따릅니다.

리소스를 반환하는 멱등 재요청은 현재 상태를 돌려주고 그 사이 삭제됐으면 404입니다. 시험 준비 POST는 원생성 결과를 반환하는 예외입니다. 주소 삭제는 키 보존 기간 안의 같은 키·요청이면 204, 새 키로 이미 삭제한 주소를 요청하면 404입니다.

### 기존 거래를 ID 없이 찾기

구매자는 `GET /api/refund-requests`에서 본인의 취소·반품 요청, `GET /api/refunds`에서 본인의 금융 환불을 찾습니다. 두 목록은 `page=0`, `size=20`이 기본이며 `page≥0`, `size=1..100`을 사용합니다. 정렬은 `createdAt DESC,id DESC`이고 본인 내역이 없으면 `200`과 빈 `items`를 반환합니다. 다른 구매자의 행과 전체 건수는 포함하지 않습니다. Payment는 결제의 구매자 조건을 DB에서 적용한 뒤 환불을 조회·집계합니다. 금융 환불 목록은 금액·상태·버전·생성 시각의 요약이며 SAGA 진행은 기존 단건 조회에서 확인합니다.

데모 UI는 상품·주문·결제·쿠폰·반품·환불 등 제공된 목록에서 대상을 선택합니다. 생성·조회 결과의 ID와 대상별 버전은 다음 작업에 이어 쓰고, 결과의 **데이터 흐름 보기**는 해당 거래만 읽습니다. 다른 계정의 전체 목록이나 임의 금융 동의를 만들지는 않습니다. 대상 계정·고객 확인 증빙 등 외부에서 전달해야 하는 값은 본인이 확인한 응답을 명시적으로 연결합니다.

### 용량 부족 시 신규 접수

`POST /api/orders`와 `POST /api/orders/{orderId}/payment-retries`는 [접수 제한·재개 기준](policies.md#신규-접수-제한과-재개)에 따라 신규 작업만 `503 COMMON_CAPACITY_EXCEEDED`와 `Retry-After: 30`으로 거절합니다. 기존 멱등 결과·본문 충돌 판정이 먼저이며 신규 예약·업무 저장 전 검사합니다. 거절을 멱등 완료로 캐시하지 않고 같은 키·본문의 재시도를 허용합니다. 신규 선착순 이벤트 주문은 이 판정과 매진·종료 판정 뒤 [이벤트별 동시 실행 상한](policies.md#선착순-상품-이벤트)을 넘으면 같은 코드와 `Retry-After: 1`로 거절합니다. 기존 거래의 취소·환불·지급·재처리·조회는 이 gate의 대상이 아닙니다. DB 주문 경로에 적용하며, 기본 Compose의 실제 수집기·필수 그룹 offset을 준비한 뒤 60초 회복 조건을 충족하면 접수를 엽니다. 관측 연결·운영 중단 설정은 [개발 환경](development.md#신규-주문-접수-제한)을 따릅니다.

## 기능 목록

메서드·경로·DTO·권한·필수 헤더·응답 코드는 [OpenAPI](openapi.json)의 `paths`를 기준으로 합니다. 실행 중인 API는 Gateway의 [Swagger UI](http://localhost:8080/swagger-ui.html)에서 서비스별로 탐색합니다. 내부·모의 API는 소유 앱의 `/v3/api-docs`에서 확인하며 Gateway를 거치지 않습니다.

| 찾을 기능 | 소유 서비스 | 사용·연동 안내 |
| --- | --- | --- |
| 계정·세션·주소·상품·재고·이벤트 | Commerce | [인증·역할](architecture.md#사용자-인증과-내부-신원-전달), [일반 주문](development.md#일반-주문-api) |
| 쿠폰 발급·조회·월간 혜택 | Commerce | [쿠폰 사용](#쿠폰-사용-순서), [월간 혜택 운영](#월간-혜택-운영-조회와-오류-교정) |
| 주문·견적·발송·취소·반품 | Commerce | [주문·환불](#주문환불-사용-순서), [실패 환불 종결](#실패-환불의-운영-종결) |
| 결제·마일리지·금융 환불·격리 운영 | Payment | [이벤트 연동](#이벤트-연동), [정산 대조](#정산-대조-조회), [격리 운영](#payment-격리-원문-운영) |
| 정산·지급·배치·지급 작업·격리 운영 | Settlement | [환불 SAGA](#settlement-환불-saga), [정산 정책](policies.md#정산재처리), [격리 운영](#격리-원문-운영) |
| 거래 알림·발송 이력·재처리 | Commerce | [거래 알림](#거래-알림-사용-순서) |
| PG·간편결제·지급 대역·시험 계정·장애 설정 | Finance Mock | [금융 대역](#금융-대역-조회와-장애-설정) |

같은 `/api/buyers`·`/api/sellers` 접두사라도 기능 소유 앱이 다릅니다. Gateway는 `x-owner-service`의 개별 메서드·경로로 라우팅하며, 마일리지는 Payment, 정산·지급은 Settlement가 소유합니다.

## 쿠폰 사용 순서

1. 본인 쿠폰을 조회하고 견적·주문에 쿠폰 ID 한 개를 보냅니다. 할인액·부담자·수수료는 서버가 확정하며 마일리지를 함께 사용하지 않습니다.
2. 판매자는 자기 캠페인만 등록·발급합니다. 플랫폼 혜택은 가입·월간 규칙으로 자동 발급하며 구매자·운영자가 금액·수신자를 지정하지 않습니다.
3. 예약·결제·환불은 원쿠폰·최초 금액으로 이어갑니다. 종류·기한·대상·부담·예산·금액식은 [쿠폰 정책](policies.md#쿠폰)을 따릅니다.

## 월간 혜택 운영 조회와 오류 교정

Commerce의 `/internal/coupon-issue-runs`에서 월간 회차 목록·단건을 조회하고 `/{runId}/replacements`로 교정합니다. 검증한 운영자만 호출하며 Gateway에 공개하지 않습니다. 필드·필터·정렬·페이지는 [OpenAPI](openapi.json)를 따릅니다.

`/{runId}/targets`는 회차에 고정한 `rankNo ASC,buyerId ASC` 순서로 선정 순위·구매액·등급·발급 쿠폰 ID·상태를 조회합니다. 현재 주문으로 다시 집계하지 않습니다. 없는 회차는 404이며 대상 확정 전에는 빈 목록입니다. 페이지 조건은 OpenAPI를 따릅니다.

교정 POST는 `Idempotency-Key`와 `{expectedVersion, reasonCode: "AGGREGATION_ERROR"}`를 받습니다. 구매자·등급·금액·마감·기간을 지정할 수 없습니다. [발급 0건·안전한 중단 등 교정 조건](policies.md#월간-회차-검증과-오류-교정)을 충족하면 취소·대체 회차·감사·멱등 결과를 원자 저장합니다.

성공은 `201`과 새 회차의 Location이며 발급 완료가 아닙니다. 동일 키·본문은 상태·버전 검사보다 먼저 기존 결과를 반환하고 다른 본문은 409입니다. 새 runId·replacesRunId와 원회차의 replacementRunId로 연결하며 Scheduler가 이후 발급을 시작합니다. 저장 제약은 [회차 모델](data-model.md#coupon_issue_runs)을 따릅니다.

## 주문·환불 사용 순서

1. 상품·이벤트·쿠폰·마일리지를 조회하고 order-quotes로 서버 계산을 미리 확인합니다. 미리보기는 재고·마일리지를 예약하지 않습니다.
2. POST /api/orders가 주소·가격·쿠폰·이벤트 참여를 검증하고 주문/예약/Outbox를 함께 저장합니다. 이벤트 참여도 이 주문 명령 안에서 생성합니다.
3. PaymentRequested 수신 뒤 Payment가 마일리지를 예약하고 외부 수단이 있으면 호출합니다. GET 주문/결제에서 확정과 미확정을 구분합니다. 외부 승인 결과와 마일리지 차감을 모두 반영한 뒤 전체 결제를 확정합니다.
4. 환불은 주문의 refund-requests에서만 접수합니다. 구매자가 Payment에 임의 금액으로 직접 환불을 만들지 않습니다. 미발송 취소와 발송후 반품 승인, 기한내 접수와 기한후 처리를 구분합니다.
5. 판매자 승인 전 반품은 구매자가 철회할 수 있습니다. 금융 실패 뒤 운영 종결은 외부확정거절·미복원·예약해제·고객확인 근거를 모두 확인한 경우만 허용하고, 미확정이면 거절합니다.
6. 모의 배송완료 반영과 기한 후 자동 구매확정은 내부 스케줄 작업이며 구매자용 수동 조기확정 API는 없습니다. 정산·지급은 해당 이벤트와 예약 상태를 확인합니다.

상품권 요청은 결제수단 enum 검증에서 `400 COMMON_VALIDATION_FAILED`로 거절합니다.

## 이벤트 연동

### 실제 이벤트 기록 관측

운영자는 `GET /internal/commerce-event-snapshot`, `/internal/payment-event-snapshot`, `/internal/settlement-event-snapshot`으로 각 소유 DB의 Outbox·Inbox 메타데이터를 읽습니다. Gateway에는 공개하지 않으며 데모 UI는 기존 Demo Control의 서비스별 운영자 자격 중계를 사용합니다. 일반 목록 API와 달리 한 번의 제한된 관측을 반환합니다.

필수 `ids`는 UUID 1~20개의 쉼표 목록(`style=form`, `explode=false`)입니다. eventId·집계 ID·반영 결과 ID와 허용된 업무 참조(orderId·paymentId·refundId·requestId·payoutId·batchId)로 찾습니다. 계정·주소·상품 ID에 의한 전체 거래 조회는 제공하지 않습니다. 서비스는 최대3초의 읽기 트랜잭션에서 최대50개와 `truncated`·`limit`·KST `observedAt`을 반환하며 전체 건수·페이지를 주장하지 않습니다. raw_body와 payload는 제외합니다.

같은 eventId로 발행 측과 소비자별 기록을 연결합니다. Outbox의 발행 파티션·offset과 Inbox의 최초 수신 위치는 재발행 때 달라질 수 있습니다. PUBLISHED는 저장된 발행 확인, RECEIVED·WAITING·APPLIED·REVIEW_REQUIRED는 수신·대기·이벤트 반영·확인 필요 상태이며 금융 최종 성공과 다릅니다. 조회 실패·자격 미설정·상한으로 미조회한 범위는 수신 기록 없음과 구분합니다. HTTP 응답과 서비스별 후속 관측은 하나의 원자적 추적이 아닙니다.

### 이벤트 전달 계약

비동기 업무 명령은 Kafka를 사용하고 같은 작업의 HTTP 명령을 중복 제공하지 않습니다. Envelope와 각 payload의 필수 필드는 OpenAPI components 및 x-event-contracts에 정의합니다. topic은 `commerce-settle.<producer>.v1`, consumer group은 `<소비자>.v1`; key는 아래 기준입니다. 발행자는 원래 eventId를 유지합니다. 소비자는 consumer+eventId와 도메인 업무 유일성을 함께 검사하고, 선행 결제/원장이 없으면 영속 대기로 남깁니다.

| 이벤트 | 생산자 | 소비자 | key | payload |
| --- | --- | --- | --- | --- |
| `OrderPaymentRequested` | commerce | payment | `orderId` | `OrderPaymentRequestedPayload` |
| `PaymentAccepted` | payment | commerce | `paymentId` | `PaymentAcceptedPayload` |
| `PaymentCancellationRequested` | commerce | payment | `orderId` | `PaymentCancellationRequestedPayload` |
| `PaymentCancellationRejected` | payment | commerce | `paymentId` | `PaymentCancellationRejectedPayload` |
| `PaymentConfirmed` | payment | commerce, settlement | `paymentId` | `PaymentConfirmedPayload` |
| `PaymentDeclined` | payment | commerce | `paymentId` | `PaymentDeclinedPayload` |
| `PaymentCancelled` | payment | commerce | `paymentId` | `PaymentCancelledPayload` |
| `RefundApproved` | commerce | payment | `paymentId` | `RefundApprovedPayload` |
| `RefundAccepted` | payment | commerce | `paymentId` | `RefundAcceptedPayload` |
| `RefundReservationRequested` | payment | settlement | `paymentId` | `RefundReservationRequestedPayload` |
| `RefundReservationResult` | settlement | payment | `paymentId` | `RefundReservationResultPayload` |
| `RefundFinancialCompleted` | payment | settlement | `paymentId` | `RefundFinancialCompletedPayload` |
| `RefundLedgerReversed` | settlement | payment | `paymentId` | `RefundLedgerReversedPayload` |
| `RefundCompleted` | payment | commerce | `paymentId` | `RefundCompletedPayload` |
| `RefundOrderRestored` | commerce | payment | `paymentId` | `RefundOrderRestoredPayload` |
| `RefundReleaseRequested` | payment | settlement | `paymentId` | `RefundReleaseRequestedPayload` |
| `RefundReservationReleased` | settlement | payment | `paymentId` | `RefundReservationReleasedPayload` |
| `RefundFailed` | payment | commerce | `paymentId` | `RefundFailedPayload` |
| `PurchaseConfirmed` | commerce | settlement | `paymentId` | `PurchaseConfirmedPayload` |
| `PaymentNoticeRequested` | payment | commerce-notifications | `paymentId` | `PaymentNoticeRequestedPayload` |
| `SettlementNoticeRequested` | settlement | commerce-notifications | `batchId` | `SettlementNoticeRequestedPayload` |

OrderPaymentRequested·PaymentConfirmed는 할인 부담별 S/P와 정산 기준 B를 전달합니다. Settlement는 SOURCE의 E/M/P와 ALLOCATION의 판매자/수수료를 각각 B로 대조하고 원액으로 환불 상쇄합니다.

`RefundCompleted`는 외부 환불·마일리지 복원·정산 원장 상쇄 후 커머스에 주문 복구를 전달하는 기존 v1 이벤트입니다. 이 발행 시점의 FinancialRefund는 `INTERNAL_PENDING`이며 전체 환불 완료를 뜻하지 않습니다. 커머스는 로컬 복구와 `RefundOrderRestored` Outbox를 함께 저장하고, Payment는 그 확인을 수신·반영한 뒤 `COMPLETED`로 전이합니다.

Kafka key는 위 표를 따르며 envelope의 aggregateId와 같다는 뜻은 아닙니다. aggregateVersion은 발신 집계의 버전이고 서로 다른 서비스의 버전을 하나의 순서로 비교하지 않습니다. 재전달은 원래 이벤트/업무 키를 사용하며, 늦은 승인 원장·자동 확정은 다음 정산에서 누락 없이 반영합니다. 금융 이벤트에는 주소·비밀번호·로그인 토큰을 싣지 않습니다.

Commerce는 `PaymentAccepted`로 본인 주문의 paymentId만 연결하며 접수 통지로 금액·주문 상태·재고를 바꾸지 않습니다. 같은 수신 경계가 결제·취소·환불 결과도 인계하고, 현재 회차와 맞는 결과·혜택·감사·Outbox를 원자 반영합니다. 선행 주문은 영속 대기로 기다리며 충돌은 보존합니다. [활성화·복구](development.md#결제-접수-결과-수신)와 [Inbox 저장 계약](data-model.md#inbox)을 따릅니다.

확정 거절 뒤 `payment-retries`로 만든 요청은 기존 Payment ID를 유지합니다. Payment는 이전보다 높은 주문 버전·사용하지 않은 새 회차·동일 당사자/상품/금액/정책/결제수단을 확인하고 마일리지를 다시 예약한 뒤 회차별 승인 작업과 `PaymentAccepted`를 함께 저장합니다. 처리 대기 상태는 DECLINED이며 새 승인 시 PROCESSING으로 전이합니다. UNKNOWN은 원거래 조회를 유지합니다. 과거 승인 시도·예약·원장·이벤트는 보존하고, 새 `PaymentAccepted`·`PaymentDeclined` 식별자는 회차별로 구분합니다. 취소가 먼저 처리된 새 회차는 승인하지 않습니다.

Settlement는 `PaymentConfirmed`·`PurchaseConfirmed`와 아래 환불 SAGA 요청 v1을 수신합니다. 종류·버전·생산자 토픽·key(paymentId)·필드 타입을 검사하며 같은 토픽의 다른 eventType은 저장 없이 ACK합니다. 결제는 최초 원장, 구매확정은 지급 가능 상태로 반영합니다. 선행 결제가 없으면 기다리고, 나중에 결제가 도착하면 그 대기만 재개합니다. 원문 격리·실패·대기 소진·재개는 [Inbox 계약](data-model.md#inbox), 활성화·offset은 [Kafka 실행](development.md#kafka-전달-기반)을 따릅니다. 세 서비스의 실제 연동 확인은 [왕복 smoke](development.md#주문결제정산환불-왕복)에서 다룹니다.

Payment는 commerce 토픽에서 `OrderPaymentRequested`·`PaymentCancellationRequested`·`RefundApproved`·`RefundOrderRestored` v1만 처리합니다. 같은 토픽의 다른 eventType(`PurchaseConfirmed` 등)은 저장·격리 없이 ACK합니다. eventType을 읽을 수 없거나 처리 대상 종류의 버전·key·필드가 계약에 맞지 않으면 [원문 격리](development.md#kafka-전달-기반)를 따릅니다.

### Settlement 환불 SAGA

Settlement는 환불 요청을 Inbox와 같은 트랜잭션에 반영하고 결과를 자기 Outbox에 저장한 뒤 `commerce-settle.settlement.v1`으로 발행합니다. 결과 이벤트의 key와 aggregateId는 `paymentId`, aggregateVersion은 정산 항목 버전입니다. 결제와 정산 항목은 1:1이라 항목이 생기기 전의 `WAITING`도 뒤의 결과보다 먼저 나갑니다. Relay는 `app.outbox.enabled`(기본 `false`)로 켜며, 꺼져 있어도 결과는 Outbox에 PENDING으로 쌓입니다.

1. `RefundReservationRequested`: 항목이 `WAITING_CONFIRMATION`·`PAYABLE`이고 지급에 배정되지 않았으면 `REFUND_RESERVED`로 바꾸고 직전 상태·순액으로 ACTIVE `REFUND` 예약을 만든 뒤 `RESERVED`(reservationId)로 답합니다. 지급 선점과 같은 항목 행을 조건부 UPDATE로 다투므로 둘 중 하나만 이깁니다.
2. 거절은 상태를 바꾸지 않고 `REJECTED`와 reasonCode로 답합니다. `PAYOUT_RESERVED`(지급 배정·지급 완료, 지급 예약 후 환불 미지원), `ALREADY_REFUNDED`(환불 완료·다른 환불 예약 중), `REFUND_AMOUNT_MISMATCH`(요청 netAmount ≠ 항목 순액)입니다.
3. 결제 항목이 아직 없으면 Inbox를 WAITING으로 두고 처음 한 번만 `WAITING`으로 답합니다. 구매확정 대기와 같이 1~60초 간격 10회까지 다시 판정해 `RESERVED`·`REJECTED`로 답하고, 소진하면 결과 없이 `REVIEW_REQUIRED`로 남깁니다.
4. `RefundFinancialCompleted`: 같은 환불의 ACTIVE 예약이 있고 외부·마일리지 금액이 원거래와 같으면 최초 원장 5 leg마다 반대 부호 줄(0원 포함)을 만들고 항목 `REFUNDED`, 예약 CONSUMED로 바꾼 뒤 `RefundLedgerReversed`로 답합니다. 예약이 없거나 금액이 다르면 결과 없이 `REVIEW_REQUIRED`입니다.
5. `RefundReleaseRequested`: 같은 결제·같은 환불의 ACTIVE 예약을 풀고 항목을 직전 상태로 되돌린 뒤 `RefundReservationReleased`로 답합니다. 상쇄가 끝났거나 맞는 예약이 없으면 `REVIEW_REQUIRED`입니다.
6. 같은 환불의 재요청(다른 eventId)은 결과(예약·거절)나 대기 여부와 상관없이 먼저 받은 원요청의 결제·순액과 대조합니다. 같으면 결과를 다시 보내지 않고(원요청이 `REVIEW_REQUIRED`면 함께 `REVIEW_REQUIRED`), 다르면 `REVIEW_REQUIRED`입니다. 원래 결과는 Outbox가 전달하며, 원요청이 대기 중이면 대기를 마친 원요청이 냅니다.

판매자 알림 요청 `SettlementNoticeRequested`(key·aggregateId=batchId)도 같은 Outbox로 보냅니다. 일배치 확정 때 `SETTLEMENT_READY`, 지급 성공 반영 때 `PAYOUT_COMPLETED`, 확정 거절 반영 때 `PAYOUT_ACTION_REQUIRED`(`PROVIDER_DECLINED`)이며 업무 변경과 같은 트랜잭션에 저장합니다.

Payment는 Commerce의 `RefundApproved`·`RefundOrderRestored`와 Settlement의 환불 응답 v1을 같은 Inbox 경계로 수신합니다. 원결제·예약·기대 단계가 맞지 않는 응답은 반영하지 않고 `REVIEW_REQUIRED`(`REFUND_SETTLEMENT_REPLY_REJECTED`)로 보존하며 같은 결과의 재전달은 무변경입니다. 발행 이벤트는 위 표, 저장 전이는 [refunds](data-model.md#refunds)·[refund_sagas](data-model.md#refund_sagas), 수신 활성화는 [실행 안내](development.md#kafka-전달-기반)를 따릅니다. 외부 환불은 `app.refund.gateway=http`일 때 원승인 Finance Mock에 HTTP로 연결하며, 기본 `stub`은 개발용 즉시 성공 대역입니다. 원승인의 provider·외부 거래·시나리오 버전과 외부 금액만 사용하고, 응답의 원승인 ID·환불 키·금액·작업·버전이 일치할 때만 결과를 반영합니다. 응답 유실·처리 중·조회 장애는 예약을 유지하며 같은 환불 키를 조회하고, 거래 부재 코드가 확인된 조회 404에서만 원키로 재전송합니다. 실제 Commerce·Payment·Settlement 왕복의 범위는 [왕복 smoke](development.md#주문결제정산환불-왕복)를 따릅니다(지급 제외). `GET /api/refunds/{refundId}`의 `INTERNAL_PENDING`은 원장 상쇄·주문 복구 확인 대기입니다.

PaymentAccepted·RefundAccepted 이벤트로 주문 조회에 금융 처리 ID를 먼저 연결합니다. 결제/실패/취소 결과는 현재 paymentCycleId와 대조하며 오래된 회차 결과로 새 예약을 해제하지 않습니다.

지급 전 Settlement는 Commerce의 `ProductSettlementContext`로 상품 소유 관계·판매자 상태·수취 정보를 확인합니다. 논리삭제 상품도 조회하며 정지·삭제 판매자는 해당 상태를 돌려줍니다. 필드·권한은 OpenAPI, 지급 판정·실패·사본 보존은 [정산 정책](policies.md#정산재처리)을 따릅니다.

Order는 deliveryBusinessDays·deliveryCalendarVersion·shippedAt·deliveryDueAt·deliveredAt·refundDeadlineAt을 반환합니다. 서버만 모의 배송완료를 반영하며 구매자·판매자가 도착/환불 마감을 지정할 수 없습니다. PurchaseConfirmed는 deliveredAt·refundDeadlineAt·confirmedAt을 전달해 배송완료와 환불 마감 이후의 확정임을 보존합니다.

PG 입금은 금융 Mock의 내부 매분 작업으로 원승인당 한 번 반영합니다. MockApproval의 fundingAccountRef는 Payment 서버 설정이며 원승인에 고정합니다. MockTransaction의 pgReceiptDueAt·pgReceivedAt·pgReceivedAmount로 예정/실제 입금과 입금 전 환불 상쇄를 조회합니다. 선지급 재원은 항상 충분하며 시험 전에 전체 지급·환불 자금을 준비합니다. [현금·복구 정책](policies.md#선지급-재원과-pg-회수)을 따릅니다.

### Payment 격리 원문 운영

Payment 격리 원문 API(`/internal/payment-quarantines/**`·`/internal/payment-quarantine-deliveries/**`)는 `operator:<식별자>` 자격만 받고 Gateway에 공개하지 않습니다. Settlement [격리 원문 운영](#격리-원문-운영)과 같은 요청·응답 모양·오류·멱등·감사 규칙이며, 경로 접두사와 응답 스키마 이름(`Payment` 접두사)만 다릅니다. 요청 본문은 같은 `QuarantineReplayRequest`입니다.

- 조회는 원문·키 bytes 대신 수신 위치, 오류 코드, 원문 크기와 SHA-256만 반환합니다. 원문 확인은 DB 권한 경로로 합니다.
- 재처리는 원문 `version`, 재개는 발행 작업 `version`을 `expectedVersion`으로 받고, `reasonCode`는 `^[A-Z][A-Z0-9_]{0,79}$`입니다. 재처리는 원래 topic·key·bytes 그대로 REPLAY 작업을 만들고, 재개는 시도를 다 쓴 같은 작업을 PENDING·시도 수 0으로 되돌립니다. 202는 발행 접수일 뿐이며 Kafka 발행 완료(`PUBLISHED`)와 업무 반영은 따로 확인합니다.
- 원문·작업 버전이 다르면 `409 COMMON_VERSION_CONFLICT`, 같은 원문의 REPLAY가 발행 중이면 재처리와 소진 REPLAY의 재개 모두 `409 QUARANTINE_REPLAY_ACTIVE`, REVIEW_REQUIRED가 아닌 작업의 재개는 `409 QUARANTINE_DELIVERY_NOT_RESUMABLE`, 없는 원문·작업은 404입니다.
- 같은 `Idempotency-Key`·본문 재요청은 처음 결과의 현재 상태를 돌려주고, 같은 키 다른 본문은 `409 COMMON_IDEMPOTENCY_CONFLICT`입니다. 멱등 기록은 운영 결제 작업 재처리와 같은 `payment.idempotency_records`에 남고, 같은 운영자·작업·키의 요청은 직렬화되어 동시 재요청도 한 번만 반영됩니다. 요청자·사유는 작업·재개 이력과 `payment.audit_logs`에 남습니다(correlation은 격리 ID).

### 정산 대조 조회

`GET /internal/payment-reconciliation-events`는 Payment가 소유하며 Settlement 전용 서비스 자격만 허용합니다. 사용자·Gateway·일반 운영자 자격으로 호출하지 않고 Gateway에 공개하지 않습니다. 다른 서비스 DB 직접 조회를 대체하는 읽기 전용 계약이며 금융 실행 API가 아닙니다.

- 같은 회차의 첫 페이지 재개는 선택 `snapshotAt`을 유지합니다. cursor와 snapshotAt이 충돌하면 400이며 마감 이전부터 열린 원천 트랜잭션이 있으면 503입니다.
- 최초 요청은 `cutoffAt`(오프셋을 포함한 시각)과 `size`(1~100, 기본 100)를 받습니다. Payment는 검증한 최대 업무 트랜잭션 시간보다 긴 안전 여유가 지난 마감만 허용하며, 미래 마감·준비 전 기준선은 `409 RECONCILIATION_CUTOFF_NOT_READY`입니다. 원천 이력·안전 여유를 확인할 수 없으면 503으로 중단하며 현재 상태로 대체하지 않습니다.
- `occurredAt≤cutoffAt`인 `PaymentConfirmed`·`RefundFinancialCompleted`를 **발행 여부와 무관하게** 제공합니다. E·M·P·B·F·N은 원거래에 고정한 금액이며 환불 이벤트도 양수 원금액을 반환합니다. 대조 측은 이벤트 종류에 따라 최초 반영·상쇄를 구분합니다. 이후 환불·정책 변경으로 과거 금액을 다시 계산하지 않습니다.
- 내부 대조는 회차를 이어 읽는 이벤트 피드이므로 `createdAt DESC,eventId DESC`의 keyset cursor를 사용합니다. 응답은 공통 목록의 `items/page/size/totalElements/totalPages`와 `cutoffAt/snapshotAt/nextCursor`를 반환합니다. 최초 조회의 snapshotAt·원천 조회 경계·cutoffAt·size를 cursor에 묶고, 이후 요청에서는 같은 cutoffAt·size와 cursor를 사용합니다. 기준선 이벤트 집합·금융 값은 불변이며 발행 위치만 진행에 따라 달라질 수 있습니다. 잘못된 cursor·다른 조건의 재사용은 400입니다.
- `publicationState=PENDING`은 미발행 또는 ack 뒤 위치 저장 전 중단을 포함합니다. `topic`은 고정된 원천 토픽이며 `partition/offset/publishedAt`은 함께 null입니다. `PUBLISHED`이면 세 필드가 모두 있고 위치는 ack 결과입니다. null을 0으로 바꾸거나 미발행 이벤트를 페이지에서 제외하지 않습니다.
- Settlement는 `eventId`로 자기 Inbox를 먼저 확인합니다. 이미 APPLIED인 이벤트는 재발행 위치가 더 뒤로 옮겨져도 원장을 대조합니다. 수신 기록이 없는 위치 미확정 건은 발행 대기로 남깁니다. 자기 Consumer 그룹의 커밋 offset·Inbox·격리 기록에 따른 나머지 판정은 [정산 대조](policies.md#정산-대조)을 따릅니다.

### Settlement 업무 Inbox 운영

OPERATOR 자격으로 사유·이벤트 종류별 요약을 보고, 확인 대상 목록의 ID·버전·원문 크기·SHA-256을 대조한 뒤 고정 대상 재판정 실행을 만듭니다. 원문은 반환하지 않습니다. 내부 운영 API이므로 Gateway에 공개하지 않습니다.

| 경로 | 사용 흐름 |
| --- | --- |
| `GET /internal/settlement-inbox-summary` | 사유·이벤트별 건수와 현재 확인 상태의 최장 대기 나이(초) |
| `GET /internal/settlement-inboxes`, `GET /internal/settlement-inboxes/{inboxId}` | 확인 대상 조건 조회·단건. 목록의 receivedBefore는 오프셋 포함 시각이며 생성 때 같은 조건을 제출 |
| `GET/POST /internal/settlement-inbox-rejudgements` | 실행 목록·생성. 조건·예상 수와 대상 ID/expectedVersion을 대조해 최대 100건 고정 |
| `GET /internal/settlement-inbox-rejudgements/{runId}` | 조건·상태·실행 버전·진행 수 |
| `GET /internal/settlement-inbox-rejudgements/{runId}/targets`, `.../attempts`, `.../audits` | 대상별 판정 결과, 임대·중단·재기동 복구와 운영자·처리기의 사유/감사 이력 |
| `POST /internal/settlement-inbox-rejudgements/{runId}/pauses`, `.../resumes`, `.../cancellations` | 실행 expectedVersion과 운영 사유 코드로 중단·재개·취소 |

202는 실행 접수이며 금융 성공을 뜻하지 않습니다. 생성 응답의 Location으로 실행을 조회하고 대상 결과를 확인합니다. 같은 운영자·작업의 동일 Idempotency-Key/본문은 기존 실행을 반환하며 감사·실행을 다시 만들지 않습니다. 같은 키의 다른 본문, 버전 변경·대상 수 불일치·겹치는 실행은 409입니다. 재판정 가능한 사유와 선행 근거·중단 경계는 [업무 정책](policies.md#업무-inbox-재판정), 정확한 입력·결과·오류 코드는 [OpenAPI](openapi.json), 실행 예시는 [개발 환경](development.md#업무-inbox-관측과-재판정)을 따릅니다.

### 대조 회차와 원천별 검사

| 메서드 | 경로 | 권한·결과 |
| --- | --- | --- |
| POST | `/internal/settlement-reconciliations` | OPERATOR. 고정 `cutoffAt` 회차 생성·재개, 202와 기존/신규 회차 |
| GET | `/internal/settlement-reconciliations/{runId}` | OPERATOR. 마감·snapshot·cursor·진행 단계·상태·시도 수·오류 |
| GET | `/internal/settlement-reconciliations/{runId}/results` | OPERATOR. 원천별 결과 페이지와 로컬 검사·건수·차액·증빙 |
| GET | `/internal/settlement-reconciliations/{runId}/history` | OPERATOR. 최근 100건의 페이지 커밋·판정 변화·중단·재개 이력 |
| GET | `/internal/payment-reconciliation-checks` | SERVICE:settlement. Payment 소유 검사, `direction=PAYMENT`·`PROVIDER` 양방향 |
| GET | `/mock/payout-reconciliation` | SERVICE:settlement. 고정 대상 지급·실제 차감 건수·금액·최초 성공 시각 |
| GET | `/mock/reconciliation-integrity` | SERVICE:settlement. 현재 대역 계정·현금·예약의 불일치 건수와 확인 시각 |
| GET | `/mock/payment-reconciliation` | SERVICE:payment. 원천 PG·간편결제 승인/환불 페이지 |
| POST | `/mock/payment-reconciliation-queries` | SERVICE:payment. 같은 snapshot의 업무 키 최대 100개 읽기 전용 일괄 조회 |

원천 페이지는 `cutoffAt`·`snapshotAt`·`cursor`·`size`(1~100)를 사용합니다. 첫 응답의 snapshot과 같은 조건을 유지하며 Payment 검사에는 방향도 결속합니다. 현재 계정 검사 `currentChecks`·`checkedAt`은 과거 마감 비교와 구분합니다. 조회 실패는 503 또는 회차 `UNVERIFIED`이며 빈 일치 응답으로 대체하지 않습니다. [판정 정책](policies.md#정산-대조)과 [실행·재개](development.md#정산-대조-실행과-재개)를 따르며 모든 API는 Gateway에서 차단합니다.

### 격리 원문 운영

Settlement 격리 원문 API는 `operator:<식별자>` 자격만 받고 Gateway에 공개하지 않습니다. 조회는 원문·키 bytes 대신 수신 위치, 오류 코드, 원문 크기와 SHA-256만 반환합니다. 원문 확인은 DB 권한 경로로 합니다. 재처리는 원문 `version`, 재개는 발행 작업 `version`을 `expectedVersion`으로 받고, `reasonCode`는 `^[A-Z][A-Z0-9_]{0,79}$`입니다. 재처리는 원래 topic·key·bytes 그대로 REPLAY 작업을 만들고, 재개는 시도를 다 쓴 같은 작업을 시도 수 0으로 되돌립니다. 202는 발행 접수일 뿐이며 Kafka 발행 완료(`PUBLISHED`)와 업무 반영은 따로 확인합니다. 같은 `Idempotency-Key`·본문 재요청은 처음 결과의 현재 상태를 돌려주고, 요청자·사유는 작업·재개 이력과 `audit_logs`에 남습니다(correlation은 격리 ID). Payment도 같은 요청·응답 모양을 씁니다([Payment 격리 원문 운영](#payment-격리-원문-운영)).

### 금융 대역 조회와 장애 설정

PG A/B/C의 카드와 EASY_PAY의 시험 지갑에 승인·전액 환불을 제공하며 부분 환불은 지원하지 않습니다. 카드 승인은 잔액 차감 없이 결과를 저장하고, EASY_PAY는 자기 WALLET 계정만 선점·차감합니다. 지갑 잔액 부족도 저장된 `DECLINED` 결과로 유지합니다. 환불은 원승인·대역·성공 상태·외부 결제액을 확인하고 원승인의 금융 버전을 사용합니다.

금융 POST는 결과 커밋 후 `202`와 조회 가능한 `Location`을 반환합니다. 본문의 `status`가 성공·확정 거절을 구분합니다. 응답 유실 시험은 같은 커밋 뒤 본문 없는 `504`를 반환하며, 원키 조회 또는 같은 본문의 재요청으로 결과를 확인합니다. 원승인 전액과 다른 금액·환불을 원승인으로 삼는 요청은 `409 MOCK_REFUND_CONFLICT`, 진행 중/성공 환불이 있는 원승인의 새 환불과 같은 업무 키의 본문 충돌은 `409 COMMON_IDEMPOTENCY_CONFLICT`입니다.

두 금융 거래 GET은 `scenarioVersion` 쿼리가 필수입니다. 이는 서버가 선택한 조회 통신 프로필이며 응답의 `MockTransaction.scenarioVersion`은 원거래의 금융 실행 버전입니다. 조회 버전 변경은 원거래 결과·요청 본문을 바꾸지 않습니다. 설정 부재·조회 실패는 503, 지정한 provider·operation·businessKey의 기록 부재는 `404 MOCK_TRANSACTION_NOT_FOUND`입니다. 일반 404나 통신 오류를 거래 없음으로 해석하지 않습니다.

시험 준비 API는 검증한 `operator:<식별자>` 자격만 받습니다. POST는 운영자·작업·`Idempotency-Key`별로 원생성 결과와 `201`·같은 `Location`을 반환하고, 다른 본문 또는 이미 등록한 계정 참조·시나리오 버전은 `409`로 거절합니다. 계정 생성 재요청은 잔액 변경 뒤에도 최초 `balance=openingBalance`·예약액 0·버전 0을 반환하며, 현재 잔액·예약액·버전은 `GET /mock/accounts/{accountId}`로 확인합니다. 시나리오 `Location`은 식별 URI이며 별도 GET은 제공하지 않습니다. 입력 필드와 검증 범위는 [OpenAPI](openapi.json), 키 보존은 [시험 데이터 준비 정책](policies.md#시험-데이터-준비)을 따릅니다.

`MockScenarioCreate/MockScenario`는 승인/PAYOUT, 환불, 조회의 설정을 분리하며 PAYOUT의 환불 필드는 모두 null입니다. [상세 설정](design.md#pg-3종과-장애-시나리오)과 [조회 결과별 복구·Worker 인계](policies.md#공통-복구)를 따릅니다.

## 거래 알림 사용 순서

거래 알림은 기본 MOCK_KAKAO와 명시적 [본인 카카오 시연](development.md#본인-카카오톡-시연)을 구분합니다. 소비·발송 활성화와 격리 복구는 [실행 안내](development.md#모의-거래-알림)를 따릅니다.

1. 결제·환불은 Payment의 `PaymentNoticeRequested`, 정산·지급은 Settlement의 `SettlementNoticeRequested`를 기존 생산자 토픽에서 받습니다. 알림 소비 그룹은 `commerce-notifications.v1`이며 기존 금융 소비 그룹과 offset을 공유하지 않습니다. 알림 실패가 금융 이벤트 소비를 막지 않습니다.
2. 커머스의 발송·배송완료·자동 구매확정·취소·반품 접수/결정/입고 전이는 같은 DB 트랜잭션에 알림 항목을 만듭니다. 외부 요청 이벤트는 Inbox에 저장하고 주문/원환불의 선행 반영이 없으면 영속 대기합니다. 새 알림 요청은 기존 금융 v1 이벤트의 payload를 바꾸지 않습니다.
3. 구매자·판매자는 같은 `/api/notifications`를 사용하지만 서버는 세션의 계정 종류와 ID로 범위를 제한합니다. `Notification`의 `SENT`는 MOCK_KAKAO 수신 기록 또는 KAKAO_SELF API 성공 응답 확인이며 읽음 확인이 아닙니다. channel·isMock으로 구분합니다. 조회는 createdAt DESC,id DESC 정렬이며 COLLECTING 본문은 마감 전 빈 값입니다.
4. 운영자는 내부 발송 조회·시도 이력·원키 재처리만 사용합니다. 사용자용 임의 발송·수신자 지정·본문 수정 API는 없습니다. 재처리의 expectedVersion·Idempotency-Key·사유를 검증하고 `Location`으로 같은 발송을 조회합니다.
5. 모의 수신 조회는 기록 없음 또는 실제 채널 발송이면 404, 모의 원결과 존재 시 200입니다. 확정 거절도 원결과로 보존하며 재요청으로 성공을 만들지 않습니다. 알림 발송은 인프로세스 어댑터이며 별도 공개 Mock HTTP 서버를 만들지 않습니다. [알림 정책](policies.md#거래-알림)과 [저장 구조](data-model.md#거래-알림)가 복구·중복·묶음 기준입니다.

## 오류와 완료 기준

공통 HTTP 오류는 [협업 규칙](../CONTRIBUTING.md#오류), 업무별 code·조건·응답은 [OpenAPI](openapi.json)의 해당 작업을 기준으로 합니다. 사람이 읽는 detail 대신 code로 분기하고, 추가 상태가 필요하면 해당 리소스를 조회합니다.

구조 검증은 JSON·로컬 ref·경로 변수·operationId·명시 권한/오류·DTO 검사를 다룹니다. HTTP·Gateway·DB·금융 동시성은 [개발·검증](development.md#개발검증)의 해당 시험으로 확인합니다. OpenAPI 형식 기준은 [공식 OAS3.1.1](https://spec.openapis.org/oas/v3.1.1.html)입니다.

주문에 연결된 알림의 운영 재처리는 [보존 기한](policies.md#보존과-삭제) 안에서만 허용합니다. 기한 초과는 기존 상태 충돌 409이며 보존 보류가 재처리 기한을 연장하지 않습니다. 이미 정리한 알림은 조회에서 404이고 늦은 원업무 중복 수신으로 본문·발송을 다시 만들지 않습니다.
정산·지급 알림은 Settlement 소유 배치 종결 시각을 내부 묶음 조회로 확인합니다. 미종결은 closedAt=null이며, 없는 ID는 결과에서 생략합니다. Commerce는 미확인 원천의 재처리를 503으로 중단하고 기존에 완료된 동일 멱등 요청은 재사용합니다. 배치별 알림 보존 보류는 Commerce가 삭제와 직렬화하며 지급의 금융 보류를 바꾸지 않습니다.

### 실패 환불의 운영 종결

인증된 구매자가 본인 `FAILED_REVIEW`·미입고 요청에 `closure-confirmations`를 호출하면 서버가 원환불·구매자·요청/주문 버전·확인 시각을 보존하고 확인 ID를 반환합니다. 운영자는 그 ID를 기존 `customerConfirmationRef`로 전달합니다. 직접 작성한 문구나 운영자의 고객 확인 자기신고는 증빙이 아닙니다. 구매자 확인은 원외부 환불 확정 거절과 실제 예약 해제 이후여야 하며, 그 뒤 주문/요청 버전이 달라졌으면 새 확인이 필요합니다.

생성 응답은 `201 Created`이며 `Location`은 `/api/refund-requests/{requestId}/closure-confirmations/{confirmationId}`로 생성한 확인 기록을 식별합니다. 같은 멱등키 재요청은 같은 확인 ID·본문·Location을 반환합니다. 별도 GET API는 제공하지 않으며 확인 내용은 생성 응답 본문으로 받습니다.

Commerce는 DB 트랜잭션 밖에서 Payment의 원환불과 `RESTORE` 원장, Settlement의 원예약·현재 항목을 읽습니다. 각 소유 서비스는 자기 DB의 한 SQL 스냅샷만 반환합니다. 타 서비스 DB를 직접 읽거나 호출자가 보낸 성공 플래그를 사용하지 않습니다. `DECLINED` 원외부 환불·마일리지 미복원, `RELEASED` 원예약·활성 예약 없음·지급 미배정과 구매자 확인을 모두 요구하며, 각 증빙을 한 번 더 읽어 확인 중 버전/내용 변경도 거절합니다(최대 네 번의 GET, 자동 재시도 없음).

이 읽기 계약은 `DECLINED` 금융 환불과 `RELEASED` 원예약을 **같은 원환불로 재실행·재활성화하지 않는 불변 종결 사실**로 정의합니다. 재실행 가능한 중간 상태나 UNKNOWN은 종결 증빙이 될 수 없습니다. 선행 구현의 종결 불변식과 스키마를 확인하기 전에는 어댑터를 활성화하지 않습니다. 반복 조회는 분산 트랜잭션이나 변경 가능한 상태의 장기 잠금을 대신하지 않습니다.

최종 저장은 같은 주문 버전을 잠금 아래 재검사하고, 운영자 신원·구매자 확인·소유 서비스 응답 사본·조회 시각을 `refund_closure_evidence`에 보존하는 로컬 트랜잭션입니다. 종결 상태·증빙 reference·감사도 함께 커밋하거나 롤백합니다. 같은 종결 키는 외부 재조회 없이 기존 결과로 연결합니다. 누락·다른 환불·고객/버전/시점 불일치·미확정·해제 전 상태는 409, 인증 설정·통신·선행 스키마 장애는 503이며 주문은 `REFUND_PENDING`을 유지합니다. 검증된 운영 종결만 기존 주문 재개 정책을 적용하며 금융 실패 자체로 PAID·자동 구매확정을 만들지 않습니다.
