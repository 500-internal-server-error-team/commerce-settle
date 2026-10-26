# 기능 API

기계 판독 기준은 [OpenAPI 3.1.1 명세](openapi.json)입니다. 요청/응답 필드·필수값·타입·오류·권한·멱등 헤더를 포함합니다. 이 문서는 기능 탐색과 서비스 연동의 기준이며 현재 업무 API가 구현됐다는 뜻은 아닙니다. [업무 정책](policies.md)과 [ERD](data-model.md)를 함께 적용합니다.

## 공통 계약

- 공개 `/api` 경로는 Gateway가 소유 앱으로 전달할 계획입니다. `/internal`·`/mock`는 직접 서비스 자격을 검증하고 Gateway에 공개하지 않습니다. 기존 라우팅 설정에는 아직 추가하지 않았습니다.
- `x-actor`는 소비자 권한, `x-owner-service`는 처리 앱입니다. 자기 역할과 리소스 소유권을 함께 검사합니다. 구매자/판매자 토큰을 서비스 자격으로 쓸 수 없습니다.
- POST 주문은 주문 리소스 생성 `201`과 Location을 반환하며 결제는 비동기입니다. 비동기 재처리·대역 접수는 `202`+Location이고 승인/환불 완료를 뜻하지 않습니다. 상태 조회 응답으로 후속 완료를 확인합니다.
- 쓰기 DTO는 additionalProperties=false입니다. 클라이언트 가격·buyerId·sellerId·providerId로 원금액/주체를 덮어쓰지 않습니다. 생성 시 서버 계산과 expectedProductVersion/expectedEventVersion/expectedTotalAmount를 비교해 달라졌으면409입니다.
- 금융 API의 순수 조회도 본인 소유를 검사합니다. 역할 오류403, 같은 역할의 타인 리소스404. `400/401/403/404/409/429/503`은 [RFC9457](https://www.rfc-editor.org/rfc/rfc9457.html)의 application/problem+json이며 code/traceId를 포함합니다.
- 목록은 고정 createdAt DESC,id DESC 정렬·page/size 범위를 사용합니다. 금액은 정수, ID는문자열UUID, 시각은UTC ISO8601입니다. 유효기간과 상태/버전 검사 기준은 policies.md가 소유합니다.
- DELETE는 query의 expectedVersion을 검증합니다. 다른 변경 요청은 DTO의 expectedVersion을 검증합니다.

## 기능 목록

| 메서드 | 경로 | 기능 | 권한 | 요청 DTO | 응답 DTO | 성공 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/buyers` | 구매자 가입 | PUBLIC | `BuyerRegistration` | `Account` | 201 |
| POST | `/api/sellers` | 판매자 가입 | PUBLIC | `SellerRegistration` | `Account` | 201 |
| POST | `/api/buyer-sessions` | buyer 로그인 | PUBLIC | `LoginRequest` | `Session` | 200 |
| POST | `/api/seller-sessions` | seller 로그인 | PUBLIC | `LoginRequest` | `Session` | 200 |
| DELETE | `/api/sessions/current` | 로그아웃 | BUYER|SELLER | `—` | `—` | 204 |
| GET | `/api/accounts/me` | 본인 계정 | BUYER|SELLER | `—` | `Account` | 200 |
| POST | `/api/password-reset-requests` | 재설정 요청 | PUBLIC | `PasswordResetRequest` | `AcceptedNotice` | 200 |
| POST | `/api/password-resets` | 비밀번호 재설정 | PUBLIC | `PasswordReset` | `—` | 204 |
| GET | `/api/sellers/me` | 본인 판매자 정보 | SELLER | `—` | `SellerProfile` | 200 |
| PUT | `/api/sellers/me` | 판매자 정보 변경 | SELLER | `SellerProfileUpdate` | `SellerProfile` | 200 |
| POST | `/api/buyers/me/addresses` | 주소 등록 | BUYER | `AddressCreate` | `Address` | 201 |
| GET | `/api/buyers/me/addresses` | 주소 목록 | BUYER | `—` | `AddressPage` | 200 |
| PUT | `/api/buyers/me/addresses/{addressId}` | 주소 변경 | BUYER | `AddressUpdate` | `Address` | 200 |
| DELETE | `/api/buyers/me/addresses/{addressId}` | 주소 삭제 | BUYER | query `expectedVersion` | `—` | 204 |
| GET | `/api/products` | 판매 상품 목록 | PUBLIC | `—` | `ProductPage` | 200 |
| GET | `/api/products/{productId}` | 판매 상품 조회 | PUBLIC | `—` | `Product` | 200 |
| POST | `/api/sellers/me/products` | 상품 등록 | SELLER | `ProductCreate` | `Product` | 201 |
| GET | `/api/sellers/me/products` | 본인 상품 목록 | SELLER | `—` | `ProductPage` | 200 |
| PUT | `/api/sellers/me/products/{productId}` | 상품 변경 | SELLER | `ProductUpdate` | `Product` | 200 |
| DELETE | `/api/sellers/me/products/{productId}` | 상품 논리삭제 | SELLER | query `expectedVersion` | `—` | 204 |
| GET | `/api/sellers/me/products/{productId}/inventory` | 본인 상품 재고 | SELLER | `—` | `Inventory` | 200 |
| POST | `/api/sellers/me/products/{productId}/inventory-adjustments` | 일반 가용 재고 조정 | SELLER | `InventoryAdjustment` | `Inventory` | 200 |
| POST | `/api/sellers/me/coupon-templates` | 쿠폰 템플릿 등록 | SELLER | `CouponTemplateCreate` | `CouponTemplate` | 201 |
| GET | `/api/sellers/me/coupon-templates` | 쿠폰 템플릿 목록 | SELLER | `—` | `CouponTemplatePage` | 200 |
| POST | `/api/sellers/me/coupon-templates/{templateId}/issues` | 구매자에게 쿠폰 발급 | SELLER | `CouponIssueRequest` | `BuyerCoupon` | 201 |
| POST | `/api/sellers/me/coupon-templates/{templateId}/closure` | 쿠폰 신규 발급 종료 | SELLER | `VersionRequest` | `CouponTemplate` | 200 |
| GET | `/api/buyers/me/coupons` | 본인 쿠폰 목록 | BUYER | `—` | `BuyerCouponPage` | 200 |
| POST | `/api/sellers/me/events` | 이름을 정해 이벤트 등록 | SELLER | `EventCreate` | `Event` | 201 |
| PUT | `/api/sellers/me/events/{eventId}` | 미공개 이벤트 변경 | SELLER | `EventUpdate` | `Event` | 200 |
| POST | `/api/sellers/me/events/{eventId}/publication` | 이벤트 공개·재고 배정 | SELLER | `VersionRequest` | `Event` | 200 |
| POST | `/api/sellers/me/events/{eventId}/closure` | 이벤트 조기 종료 | SELLER | `ReasonRequest` | `Event` | 200 |
| GET | `/api/events` | 공개 이벤트 목록 | PUBLIC | `—` | `EventPage` | 200 |
| GET | `/api/events/{eventId}` | 공개 이벤트 조회 | PUBLIC | `—` | `Event` | 200 |
| GET | `/api/events/{eventId}/gauge` | 판매 진행률·신청 상태 | PUBLIC | `—` | `EventGauge` | 200 |
| POST | `/api/order-quotes` | 서버 주문 금액 미리보기 | BUYER | `OrderQuoteRequest` | `OrderQuote` | 200 |
| POST | `/api/orders` | 일반/이벤트 주문과 결제 접수 | BUYER | `OrderCreate` | `Order` | 201 |
| GET | `/api/orders` | 본인 주문 목록 | BUYER | `—` | `OrderPage` | 200 |
| GET | `/api/orders/{orderId}` | 본인 주문 조회 | BUYER | `—` | `Order` | 200 |
| POST | `/api/orders/{orderId}/payment-retries` | 확정 거절 뒤 재예약·재시도 | BUYER | `VersionRequest` | `Order` | 202 |
| POST | `/api/orders/{orderId}/cancellation` | 미결제 주문 취소 접수 | BUYER | `ReasonRequest` | `Order` | 202 |
| POST | `/api/orders/{orderId}/refund-requests` | 기한 내 취소·반품 요청 | BUYER | `OrderRefundCreate` | `OrderRefundRequest` | 201 |
| GET | `/api/refund-requests/{requestId}` | 본인 취소·반품 진행 | BUYER | `—` | `OrderRefundRequest` | 200 |
| POST | `/api/refund-requests/{requestId}/withdrawal` | 승인 전 반품 요청 철회 | BUYER | `VersionRequest` | `OrderRefundRequest` | 200 |
| GET | `/api/sellers/me/orders` | 본인 판매 주문 목록 | SELLER | `—` | `SellerOrderPage` | 200 |
| GET | `/api/sellers/me/orders/{orderId}` | 이행 대상 판매 주문 조회 | SELLER | `—` | `SellerOrder` | 200 |
| POST | `/api/sellers/me/orders/{orderId}/shipments` | 발송 처리 | SELLER | `ShipmentRequest` | `Order` | 200 |
| GET | `/api/sellers/me/refund-requests` | 본인 반품 요청 목록 | SELLER | `—` | `OrderRefundRequestPage` | 200 |
| POST | `/api/sellers/me/refund-requests/{requestId}/decision` | 반품 승인·거절 | SELLER | `RefundDecision` | `OrderRefundRequest` | 202 |
| POST | `/api/sellers/me/refund-requests/{requestId}/receipt` | 반품 입고·재고 복구 | SELLER | `VersionRequest` | `OrderRefundRequest` | 200 |
| GET | `/api/payments` | 본인 결제 목록 | BUYER | `—` | `PaymentPage` | 200 |
| GET | `/api/payments/{paymentId}` | 본인 결제 진행 | BUYER | `—` | `Payment` | 200 |
| GET | `/api/payments/{paymentId}/attempts` | 본인 외부 승인 시도 | BUYER | `—` | `PaymentAttemptPage` | 200 |
| GET | `/api/refunds/{refundId}` | 본인 금융 환불 진행 | BUYER | `—` | `FinancialRefund` | 200 |
| GET | `/api/buyers/me/mileage` | 본인 마일리지 잔액 | BUYER | `—` | `MileageWallet` | 200 |
| GET | `/api/buyers/me/mileage/entries` | 본인 마일리지 이력 | BUYER | `—` | `MileageEntryPage` | 200 |
| GET | `/api/sellers/me/settlement-items` | 본인 정산 항목 | SELLER | `—` | `SettlementItemPage` | 200 |
| GET | `/api/sellers/me/settlement-batches` | 본인 정산 배치 | SELLER | `—` | `SettlementBatchPage` | 200 |
| GET | `/api/sellers/me/payouts` | 본인 지급 목록 | SELLER | `—` | `PayoutPage` | 200 |
| GET | `/api/sellers/me/payouts/{payoutId}` | 본인 지급 조회 | SELLER | `—` | `Payout` | 200 |
| GET | `/internal/orders/{orderId}/payment-context` | 불변 주문·금액 컨텍스트 | SERVICE:payment | `—` | `PaymentContext` | 200 |
| GET | `/internal/products/{productId}/settlement-context` | 상품 정산 기준일 | SERVICE:settlement | `—` | `ProductSettlementContext` | 200 |
| GET | `/internal/payments/{paymentId}/settlement-context` | 확정 결제·원금액 컨텍스트 | SERVICE:settlement | `—` | `SettlementContext` | 200 |
| POST | `/internal/refund-requests/{requestId}/closure` | 실패 환불의 안전한 운영 종결 | OPERATOR | `FailedRefundClosure` | `OrderRefundRequest` | 200 |
| GET | `/internal/payment-jobs` | 운영 작업 목록 | OPERATOR | `—` | `JobPage` | 200 |
| POST | `/internal/payment-jobs/{jobId}/replays` | 원업무 작업 재처리 | OPERATOR | `ReplayRequest` | `Job` | 202 |
| GET | `/internal/payout-jobs` | 운영 작업 목록 | OPERATOR | `—` | `JobPage` | 200 |
| POST | `/internal/payout-jobs/{jobId}/replays` | 원업무 작업 재처리 | OPERATOR | `ReplayRequest` | `Job` | 202 |
| POST | `/internal/settlement-batches` | 정산 배치 생성·재개 | OPERATOR | `BatchCreate` | `SettlementBatch` | 202 |
| POST | `/mock/providers/{providerId}/payments` | 모의 외부 승인 | SERVICE:payment | `MockApproval` | `MockTransaction` | 202 |
| GET | `/mock/providers/{providerId}/transactions/{businessKey}` | 원대역 거래 조회 | SERVICE:payment | `—` | `MockTransaction` | 200 |
| POST | `/mock/providers/{providerId}/payments/{externalId}/refunds` | 원외부 결제 전액 환불 | SERVICE:payment | `MockRefund` | `MockTransaction` | 202 |
| POST | `/mock/payouts` | 모의 지급 실행 | SERVICE:settlement | `MockPayout` | `MockTransaction` | 202 |
| GET | `/mock/payouts/{businessKey}` | 원지급 조회 | SERVICE:settlement | `—` | `MockTransaction` | 200 |
| POST | `/mock/accounts` | 격리 시험 계정 준비 | OPERATOR | `MockAccountCreate` | `MockAccount` | 201 |
| GET | `/mock/accounts/{accountId}` | 격리 시험 잔액 조회 | OPERATOR | `—` | `MockAccount` | 200 |
| POST | `/mock/scenarios` | 새 버전 장애 설정 | OPERATOR | `MockScenarioCreate` | `MockScenario` | 201 |
| GET | `/api/sellers/me/products/{productId}` | 본인 미공개/판매 상품 조회 | SELLER | `—` | `Product` | 200 |
| GET | `/api/sellers/me/coupon-templates/{templateId}` | 본인 쿠폰 템플릿 조회 | SELLER | `—` | `CouponTemplate` | 200 |
| GET | `/api/sellers/me/events` | 본인 이벤트 목록 | SELLER | `—` | `EventPage` | 200 |
| GET | `/api/sellers/me/events/{eventId}` | 본인 이벤트 조회 | SELLER | `—` | `Event` | 200 |

같은 `/api/buyers`·`/api/sellers` 접두사라도 기능 소유 앱이 다를 수 있습니다. Gateway는 `x-owner-service`의 개별 경로 패턴을 기준으로 라우팅합니다. 마일리지는 Payment, 판매자 정산/지급은 Settlement이며 일반 계정/상품/주문은 커머스입니다. 상품 생성은 재고 0인 inventory 행도 함께 만들고 재고 조정은 별도 명령으로 기록합니다.

## 주문·환불 사용 순서

1. 상품·이벤트·쿠폰·마일리지를 조회하고 order-quotes로 서버 계산을 미리 확인합니다. 미리보기는 재고·마일리지를 예약하지 않습니다.
2. POST /api/orders가 주소·가격·쿠폰·이벤트 참여를 검증하고 주문/예약/Outbox를 함께 저장합니다. 이벤트 참여도 이주문명령안에서생성합니다.
3. PaymentRequested 수신 뒤 Payment가 마일리지를 예약하고 외부 수단이 있으면 호출합니다. GET 주문/결제에서 확정과 미확정을 구분합니다. 외부대역수량과마일리지상태를동시에확정하기전전체결제확정금지입니다.
4. 환불은 주문의 refund-requests에서만 접수합니다. 구매자가 Payment에 임의 금액으로 직접 환불을 만들지 않습니다. 미발송 취소와 발송후 반품 승인, 기한내 접수와 기한후 처리를 구분합니다.
5. 판매자 승인 전 반품은 구매자가 철회할 수 있습니다. 금융 실패 뒤 운영 종결은 외부확정거절·미복원·예약해제·고객확인 근거를 모두 확인한 경우만 허용하고, 미확정이면 거절합니다.
6. 기한 후 자동 구매확정은 내부 스케줄 작업이며 구매자용 수동 조기확정 API는 없습니다. 정산·지급은 해당 이벤트와 예약 상태를 확인합니다.

## 이벤트 연동

비동기 업무 명령은 Kafka를 사용하고 같은작업의 HTTP 명령을 중복 제공하지 않습니다. Envelope와 각 payload의 필수 필드는 OpenAPI components 및 x-event-contracts에 정의합니다. topic은 `live-settle.<producer>.v1`, consumer group은 `<소비자>.v1`; key는 아래 기준입니다. 발행자는 원래 eventId를 유지합니다. 소비자는 consumer+eventId와 도메인 업무 유일성을 함께 검사하고, 선행 결제/원장이 없으면 영속 대기로 남깁니다.

| 이벤트 | 생산자 | 소비자 | key | payload |
| --- | --- | --- | --- | --- |
| `OrderPaymentRequested` | seller | payment | `orderId` | `OrderPaymentRequestedPayload` |
| `PaymentAccepted` | payment | seller | `paymentId` | `PaymentAcceptedPayload` |
| `PaymentCancellationRequested` | seller | payment | `orderId` | `PaymentCancellationRequestedPayload` |
| `PaymentCancellationRejected` | payment | seller | `paymentId` | `PaymentCancellationRejectedPayload` |
| `PaymentConfirmed` | payment | seller, settlement | `paymentId` | `PaymentConfirmedPayload` |
| `PaymentDeclined` | payment | seller | `paymentId` | `PaymentDeclinedPayload` |
| `PaymentCancelled` | payment | seller | `paymentId` | `PaymentCancelledPayload` |
| `RefundApproved` | seller | payment | `paymentId` | `RefundApprovedPayload` |
| `RefundAccepted` | payment | seller | `paymentId` | `RefundAcceptedPayload` |
| `RefundReservationRequested` | payment | settlement | `paymentId` | `RefundReservationRequestedPayload` |
| `RefundReservationResult` | settlement | payment | `paymentId` | `RefundReservationResultPayload` |
| `RefundFinancialCompleted` | payment | settlement | `paymentId` | `RefundFinancialCompletedPayload` |
| `RefundLedgerReversed` | settlement | payment | `paymentId` | `RefundLedgerReversedPayload` |
| `RefundCompleted` | payment | seller | `paymentId` | `RefundCompletedPayload` |
| `RefundOrderRestored` | seller | payment | `paymentId` | `RefundOrderRestoredPayload` |
| `RefundReleaseRequested` | payment | settlement | `paymentId` | `RefundReleaseRequestedPayload` |
| `RefundReservationReleased` | settlement | payment | `paymentId` | `RefundReservationReleasedPayload` |
| `RefundFailed` | payment | seller | `paymentId` | `RefundFailedPayload` |
| `PurchaseConfirmed` | seller | settlement | `paymentId` | `PurchaseConfirmedPayload` |

aggregateVersion은 발신 집계의 버전이고 서로 다른 서비스의 버전을 하나의 순서로 비교하지 않습니다. 재전달은 원래 이벤트/업무 키를 사용하며, 늦은 승인 원장·자동 확정은 다음 정산에서 누락 없이 반영합니다. 금융 이벤트에는 주소·비밀번호·로그인 토큰을 싣지 않습니다.

PaymentAccepted·RefundAccepted 이벤트로 주문 조회에 금융 처리 ID를 먼저 연결합니다. 결제/실패/취소 결과는 현재 paymentCycleId와 대조하며 오래된 회차 결과로 새 예약을 해제하지 않습니다.

지급 생성 전 Settlement는 서비스 자격의 ProductSettlementContext로 판매자 상태·수취 시험계정·버전을 확인합니다. 수취 계정과 출금 시험계정을 payouts에 사본으로 저장하고 이후 재시도에는 현재 프로필을 다시 적용하지 않습니다. Payout.succeededAt은 원대역의 최초 성공 시각이며 예정 마감·요청 접수·로컬 관측 시각과 다릅니다.

## 오류와 완료 기준

| code 예 | 상태 | 조건 |
| --- | --- | --- |
| COMMON_VALIDATION_FAILED |400|형식·수량·금액 범위 오류|
| COMMON_AUTHENTICATION_REQUIRED |401|세션 없음·만료·폐기|
| COMMON_ROLE_FORBIDDEN |403|계정/서비스 역할 혼용|
| COMMON_RESOURCE_NOT_FOUND |404|없거나 타인 소유|
| COMMON_VERSION_CONFLICT / COMMON_IDEMPOTENCY_CONFLICT |409|버전 또는 같은키 다른본문|
| COUPON_EXPIRED / COUPON_NOT_APPLICABLE |409|만료·상품 조건·할인 후0원|
| INVENTORY_INSUFFICIENT / EVENT_ADMISSION_CLOSED |409|재고/기간/참여한도/매진|
| MILEAGE_INSUFFICIENT / PAYMENT_STATE_CONFLICT |409|잔액 부족·미확정 재시도 등|
| ORDER_REFUND_WINDOW_CLOSED / ORDER_REFUND_IN_PROGRESS |409|168시간 초과·기존 환불|
| COMMON_RATE_LIMITED / COMMON_TEMPORARILY_UNAVAILABLE |429/503|제한·의존 저장소 장애|

구조 검증은 JSON·로컬 ref·경로 변수·operationId·명시 권한/오류·DTO 검사를 다룹니다. 컨트롤러·Gateway·DB·금융 동시성 검증은 구현 단계에서 별도로 수행합니다. OpenAPI 형식 기준은 [공식 OAS3.1.1](https://spec.openapis.org/oas/v3.1.1.html)입니다.
