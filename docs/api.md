# 기능 API

기계 판독 기준은 [OpenAPI 3.1.1 명세](openapi.json)입니다. 요청/응답 필드·필수값·타입·오류·권한·멱등 헤더를 포함합니다. 이 문서는 기능 탐색과 서비스 연동의 기준이며 현재 업무 API가 구현됐다는 뜻은 아닙니다. [업무 정책](policies.md)과 [ERD](data-model.md)를 함께 적용합니다.

API 이름·HTTP 메서드·명세 변경은 [API 경로와 명세 규칙](../CONTRIBUTING.md#api-경로와-명세)을 따릅니다.

## 공통 계약

- 요청 형식·범위는 `@Valid`와 OpenAPI, 업무 불변식은 도메인, 최종 경합은 DB에서 검증합니다. [검증 경계](../CONTRIBUTING.md#검증-경계)를 따릅니다. 생성/수정 시각·행위 감사 필드는 서버가 기록하며 요청 DTO로 받지 않습니다.
- 공개 `/api` 경로는 Gateway가 소유 앱으로 전달할 계획입니다. `/internal`·`/mock`는 직접 서비스 자격을 검증하고 Gateway에 공개하지 않습니다. 기존 라우팅 설정에는 아직 추가하지 않았습니다.
- `x-actor`는 소비자 권한, `x-owner-service`는 처리 앱입니다. 자기 역할과 리소스 소유권을 함께 검사합니다. 구매자/판매자 토큰을 서비스 자격으로 쓸 수 없습니다.
- 사용자 Bearer 토큰은 [Redis 세션 정책](policies.md#인증공통-api-기준)을 따릅니다. 커머스가 발급·폐기를 맡고 Gateway는 보호 요청마다 Redis 세션·계정 가드를 확인합니다. 외부 보안 스키마 accountSession과 백엔드의 Gateway 호출 자격은 [신원 전달 계약](architecture.md#사용자-인증과-내부-신원-전달)으로 구분합니다. 고정 30분이며 세션 없음·폐기/만료·버전 불일치·정지/삭제는 401, 변경 중 가드·가드 누락·Redis 검증 불가·복구 차단은 503입니다. PUBLIC은 메서드와 경로를 함께 대조합니다.
- POST 주문은 주문 리소스 생성 `201`과 Location을 반환하며 결제는 비동기입니다. 비동기 재처리·대역 접수는 `202`+Location이고 승인/환불 완료를 뜻하지 않습니다. 상태 조회 응답으로 후속 완료를 확인합니다.
- 쓰기 DTO는 additionalProperties=false입니다. 클라이언트 가격·buyerId·sellerId·providerId로 원금액/주체를 덮어쓰지 않습니다. 생성 시 서버 계산과 expectedProductVersion/expectedEventVersion/expectedTotalAmount를 비교해 달라졌으면 409입니다.
- 금융 API의 순수 조회도 본인 소유를 검사합니다. 역할 오류403, 같은 역할의 타인 리소스404. `400/401/403/404/409/429/503`은 [RFC9457](https://www.rfc-editor.org/rfc/rfc9457.html)의 application/problem+json이며 code/traceId를 포함합니다.
- 목록은 고정 createdAt DESC,id DESC 정렬·page/size 범위를 사용합니다. 금액은 정수, ID는 문자열 UUID, 시각은 UTC ISO8601입니다. 유효기간과 상태/버전 검사 기준은 policies.md가 소유합니다.
- 버전이 있는 업무 리소스의 DELETE는 query의 expectedVersion, 다른 변경은 DTO의 expectedVersion을 검증합니다. 로그인·현재 세션 로그아웃·비밀번호 재설정에는 expectedVersion을 요구하지 않습니다.
- 구매자 가입 `POST /api/buyers`는 인증 없이 호출하지만 `Idempotency-Key` 헤더가 필수입니다. 가입 전 키 범위·재요청·충돌·보존 기한은 [구매자 가입 재시도](policies.md#구매자-가입-재시도)를 따릅니다.

로그인·비밀번호 복구 요청의 계정 식별자별 제한은 [요청 제한 정책](policies.md#로그인복구-요청-제한)을 따릅니다. 제한 초과는 `429`와 `Retry-After`, 제한 저장소 확인 실패는 부작용 없이 `503`이며 계정 존재 여부를 응답으로 구분하지 않습니다.

로그아웃은 삭제 확인 뒤 204이며 삭제된 토큰의 재요청은 401입니다. 503이면 서버 폐기 성공으로 안내하지 않습니다. 비밀번호 재설정은 DB 커밋 뒤에도 503·응답 유실이 가능하므로 결과를 미확정으로 안내하고 자동 재전송하지 않습니다. 복구 후 새 비밀번호로 로그인하고 실패하면 새 복구 토큰을 요청합니다. 소비한 토큰은 401입니다. 진행 중 요청과 계정 상태 변경의 범위는 [폐기 정책](policies.md#폐기와-저장소-복구)을 따릅니다.

## 기능 목록

| 메서드 | 경로 | 기능 | 권한 | 요청 DTO | 응답 DTO | 성공 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/buyers` | 구매자 가입 | PUBLIC | `BuyerRegistration` | `Account` | 201 |
| POST | `/api/sellers` | 판매자 가입 | PUBLIC | `SellerRegistration` | `Account` | 201 |
| POST | `/api/buyer-sessions` | buyer 로그인 | PUBLIC | `LoginRequest` | `Session` | 200 |
| POST | `/api/seller-sessions` | seller 로그인 | PUBLIC | `LoginRequest` | `Session` | 200 |
| DELETE | `/api/sessions/current` | 로그아웃 | BUYER / SELLER | `—` | `—` | 204 |
| GET | `/api/accounts/me` | 본인 계정 | BUYER / SELLER | `—` | `Account` | 200 |
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
| POST | `/api/sellers/me/coupon-templates/{templateId}/closure` | 쿠폰 신규 발급 종료 | SELLER | `VersionRequest` | `CouponTemplate` | 200 |
| POST | `/api/sellers/me/coupon-templates/{templateId}/issues` | 구매자에게 판매자 쿠폰 발급 | SELLER | `CouponIssueRequest` | `BuyerCoupon` | 201 |
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
| GET | `/internal/products/{productId}/settlement-context` | 상품·판매자 지급 컨텍스트 | SERVICE:settlement | `—` | `ProductSettlementContext` | 200 |
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
| GET | `/api/notifications` | 본인 거래 알림 목록 | BUYER / SELLER | `—` | `NotificationPage` | 200 |
| GET | `/api/notifications/{notificationId}` | 본인 거래 알림 조회 | BUYER / SELLER | `—` | `Notification` | 200 |
| GET | `/internal/notification-deliveries` | 운영 발송 목록 | OPERATOR | `—` | `NotificationDeliveryPage` | 200 |
| GET | `/internal/notification-deliveries/{deliveryId}` | 운영 발송 조회 | OPERATOR | `—` | `NotificationDelivery` | 200 |
| GET | `/internal/notification-deliveries/{deliveryId}/attempts` | 발송·조회·재처리 이력 | OPERATOR | `—` | `NotificationAttemptPage` | 200 |
| POST | `/internal/notification-deliveries/{deliveryId}/replays` | 원알림 재처리 | OPERATOR | `NotificationReplayRequest` | `NotificationDelivery` | 202 |
| GET | `/internal/notification-mock/receipts/{deliveryId}` | 모의 수신 원결과 조회 | OPERATOR | `—` | `MockNotificationReceipt` | 200 |

같은 `/api/buyers`·`/api/sellers` 접두사라도 기능 소유 앱이 다를 수 있습니다. Gateway는 `x-owner-service`의 개별 경로 패턴을 기준으로 라우팅합니다. 마일리지는 Payment, 판매자 정산/지급은 Settlement이며 일반 계정/상품/주문은 커머스입니다. 상품 생성은 재고 0인 inventory 행도 함께 만들고 재고 조정은 별도 명령으로 기록합니다.

## 쿠폰 사용 순서

1. 플랫폼 혜택은 서버 규칙으로 관리하며 운영자 승인·발급 API를 두지 않습니다. 판매자는 자기 캠페인의 대상·기간·수량·예산을 정하고 issues API로 발급합니다. 서버가 SELLER 종류·부담자를 지정하며 동일 템플릿·구매자 중복을 차단합니다.
2. 가입과 웰컴 한 장 발급을 같은 트랜잭션으로 처리합니다. 월 배치는 플랫폼 전체의 구매확정 E 합계로 상위 100위와 동점자 전원을 고정하고 구매액별 할인액을 자동 적용합니다. 수혜 인원·예정 할인 총액을 계산해 발급하며 미완료 회차는 같은 업무 키로 자동 재개합니다. 판매자 쿠폰은 이 월 작업과 무관합니다.
3. BuyerCoupon은 kind·funder·선택적 sellerId·사용 가능 수단·기한을 보여줍니다. OrderQuoteRequest와 OrderCreate는 쿠폰 ID 한 개만 받고 마일리지를 함께 사용할 수 없습니다. 지원하지 않는 입력 조합은 400, 실제 쿠폰의 만료·상품·소유·금액/수단 조건 위반은 409입니다.
4. 응답·내부 컨텍스트·결제 이벤트는 총할인 couponAmount와 sellerCouponAmount(S)·platformCouponAmount(P)·settlementBaseAmount(B)를 구분합니다. 클라이언트가 할인 부담자나 금액을 지정하지 않습니다. `B=G−S=T+P=E+M+P`, 수수료는 B의 15%입니다.
5. 예약·미확정·전액 환불은 원쿠폰과 최초 E/M/P·수수료·판매자 몫을 기준으로 한 번씩 처리합니다. 할인 비용 집계와 판매자에게 실제 지급할 현금을 구분합니다. 종류·기간·금액·예산의 기준은 [쿠폰 정책](policies.md#쿠폰)을 따릅니다.

## 주문·환불 사용 순서

1. 상품·이벤트·쿠폰·마일리지를 조회하고 order-quotes로 서버 계산을 미리 확인합니다. 미리보기는 재고·마일리지를 예약하지 않습니다.
2. POST /api/orders가 주소·가격·쿠폰·이벤트 참여를 검증하고 주문/예약/Outbox를 함께 저장합니다. 이벤트 참여도 이 주문 명령 안에서 생성합니다.
3. PaymentRequested 수신 뒤 Payment가 마일리지를 예약하고 외부 수단이 있으면 호출합니다. GET 주문/결제에서 확정과 미확정을 구분합니다. 외부 승인 결과와 마일리지 차감을 모두 반영한 뒤 전체 결제를 확정합니다.
4. 환불은 주문의 refund-requests에서만 접수합니다. 구매자가 Payment에 임의 금액으로 직접 환불을 만들지 않습니다. 미발송 취소와 발송후 반품 승인, 기한내 접수와 기한후 처리를 구분합니다.
5. 판매자 승인 전 반품은 구매자가 철회할 수 있습니다. 금융 실패 뒤 운영 종결은 외부확정거절·미복원·예약해제·고객확인 근거를 모두 확인한 경우만 허용하고, 미확정이면 거절합니다.
6. 모의 배송완료 반영과 기한 후 자동 구매확정은 내부 스케줄 작업이며 구매자용 수동 조기확정 API는 없습니다. 정산·지급은 해당 이벤트와 예약 상태를 확인합니다.

상품권 요청은 결제수단 enum 검증에서 `400 COMMON_VALIDATION_FAILED`로 거절합니다. 카드·간편결제·우리 마일리지의 기존 금액·원수단 환불 계약은 유지합니다.

## 이벤트 연동

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

aggregateVersion은 발신 집계의 버전이고 서로 다른 서비스의 버전을 하나의 순서로 비교하지 않습니다. 재전달은 원래 이벤트/업무 키를 사용하며, 늦은 승인 원장·자동 확정은 다음 정산에서 누락 없이 반영합니다. 금융 이벤트에는 주소·비밀번호·로그인 토큰을 싣지 않습니다.

PaymentAccepted·RefundAccepted 이벤트로 주문 조회에 금융 처리 ID를 먼저 연결합니다. 결제/실패/취소 결과는 현재 paymentCycleId와 대조하며 오래된 회차 결과로 새 예약을 해제하지 않습니다.

지급 생성 전 Settlement는 서비스 자격의 ProductSettlementContext로 상품·판매자 일치와 판매자 상태·수취 시험계정·버전을 확인합니다. 커머스의 상품 영역이 소유 관계를 확인하고 판매자 영역이 상태·계좌·버전을 제공합니다. 이 조회 계약이 판매자 정보의 소유권을 상품이나 Settlement로 옮기지는 않습니다. 수취 계정과 출금 시험계정을 payouts에 사본으로 저장하고 이후 재시도에는 현재 프로필을 다시 적용하지 않습니다. SettlementBatch는 상품 회차 대신 sellerId+batchDate+policyVersion으로 식별합니다. Payout의 holdReason·nextAttemptAt으로 보류와 다음 시도를 안내하고 succeededAt으로 실제 성공을 구분합니다. 원래 업무 키·계정 사본·항목 연결은 보류/재시작에 유지합니다.

Order는 deliveryBusinessDays·deliveryCalendarVersion·shippedAt·deliveryDueAt·deliveredAt·refundDeadlineAt을 반환합니다. 서버만 모의 배송완료를 반영하며 구매자·판매자가 도착/환불 마감을 지정할 수 없습니다. PurchaseConfirmed는 deliveredAt·refundDeadlineAt·confirmedAt을 전달해 배송완료와 환불 마감 이후의 확정임을 보존합니다.

PG 입금은 금융 Mock의 내부 매분 작업으로 원승인당 한 번 반영합니다. MockApproval의 fundingAccountRef는 Payment 서버 설정이며 원승인에 고정합니다. MockTransaction의 pgReceiptDueAt·pgReceivedAt·pgReceivedAmount로 예정/실제 입금과 입금 전 환불 상쇄를 조회합니다. 선지급 재원은 항상 충분하며 시험 전에 전체 지급·환불 자금을 준비합니다. [현금·복구 정책](policies.md#선지급-재원과-pg-회수)을 따릅니다.

## 거래 알림 사용 순서

1. 결제·환불은 Payment의 `PaymentNoticeRequested`, 정산·지급은 Settlement의 `SettlementNoticeRequested`를 기존 생산자 토픽에서 받습니다. 알림 소비 그룹은 `commerce-notifications.v1`이며 기존 금융 소비 그룹과 offset을 공유하지 않습니다. 알림 실패가 금융 이벤트 소비를 막지 않습니다.
2. 커머스의 발송·자동 구매확정·취소·반품 전이는 같은 DB 트랜잭션에 알림 항목을 만듭니다. 외부 요청 이벤트는 Inbox에 저장하고 주문/원환불의 선행 반영이 없으면 영속 대기합니다. 새 알림 요청은 기존 금융 v1 이벤트의 payload를 바꾸지 않습니다.
3. 구매자·판매자는 같은 `/api/notifications`를 사용하지만 서버는 세션의 계정 종류와 ID로 범위를 제한합니다. `Notification`의 `SENT`는 모의 수신 확인입니다. 조회는 createdAt DESC,id DESC 정렬이며 COLLECTING 본문은 마감 전 빈 값입니다.
4. 운영자는 내부 발송 조회·시도 이력·원키 재처리만 사용합니다. 사용자용 임의 발송·수신자 지정·본문 수정 API는 없습니다. 재처리의 expectedVersion·Idempotency-Key·사유를 검증하고 `Location`으로 같은 발송을 조회합니다.
5. 모의 수신 조회는 기록 없음 404, 원결과 존재 200입니다. 확정 거절도 원결과로 보존하며 재요청으로 성공을 만들지 않습니다. 알림 발송은 인프로세스 어댑터이며 별도 공개 Mock HTTP 서버를 만들지 않습니다. [알림 정책](policies.md#거래-알림)과 [저장 구조](data-model.md#거래-알림)가 복구·중복·묶음 기준입니다.

## 오류와 완료 기준

| code 예 | 상태 | 조건 |
| --- | --- | --- |
| COMMON_VALIDATION_FAILED |400|형식·수량·금액 범위 오류|
| COMMON_AUTHENTICATION_REQUIRED |401|자격 오류·세션 없음/만료/폐기·인증 버전 불일치·정지/삭제·소비/만료된 복구 토큰|
| COMMON_ROLE_FORBIDDEN |403|계정/서비스 역할 혼용|
| COMMON_RESOURCE_NOT_FOUND |404|없거나 타인 소유|
| COMMON_VERSION_CONFLICT / COMMON_IDEMPOTENCY_CONFLICT |409|버전 또는 같은키 다른본문|
| COUPON_EXPIRED / COUPON_NOT_APPLICABLE |409|만료·상품/소유/최소 금액·허용 수단 조건 위반|
| COUPON_BUDGET_EXHAUSTED |409|판매자 캠페인의 명목 예산 또는 발급 수 소진; 플랫폼 자동 발급·가입에는 적용하지 않음|
| INVENTORY_INSUFFICIENT / EVENT_ADMISSION_CLOSED |409|재고/기간/참여한도/매진|
| MILEAGE_INSUFFICIENT / PAYMENT_STATE_CONFLICT |409|잔액 부족·미확정 재시도 등|
| ORDER_REFUND_WINDOW_CLOSED / ORDER_REFUND_IN_PROGRESS |409|배송완료+168시간 초과·기존 환불|
| PAYOUT_NON_BUSINESS_DAY |409|신규 대역 지급의 비영업일; 외부 거래 생성 없이 기존 지급 보류|
| SETTLEMENT_NO_ELIGIBLE_ITEMS |409|새 일배치에 배정할 지급 가능 항목 없음|
| NOTIFICATION_REPLAY_CONFLICT |409|완료/수집 중/유효 임대 또는 재처리 불가 상태|
| COMMON_RATE_LIMITED / COMMON_TEMPORARILY_UNAVAILABLE |429/503|제한·의존 저장소 장애·변경 중/누락된 인증 가드·인증 복구 차단|

구조 검증은 JSON·로컬 ref·경로 변수·operationId·명시 권한/오류·DTO 검사를 다룹니다. 컨트롤러·Gateway·DB·금융 동시성 검증은 구현 단계에서 별도로 수행합니다. OpenAPI 형식 기준은 [공식 OAS3.1.1](https://spec.openapis.org/oas/v3.1.1.html)입니다.
