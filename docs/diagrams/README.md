# 다이어그램 원본

GitHub 문서는 SVG를 표시합니다. 같은 이름의 HTML이 편집 원본이며 외부 스크립트 없이 열 수 있습니다. HTML의 첫 SVG와 게시용 SVG 내용을 함께 갱신합니다. 로고는 SVG 안에 포함되어 별도 네트워크 요청이 필요하지 않습니다.

| 도식 | 편집 원본 | 문서 이미지 |
| --- | --- | --- |
| 서비스 설계 | [services.html](services.html) | [services.svg](services.svg) |
| 애플리케이션과 인프라 | [runtime.html](runtime.html) | [runtime.svg](runtime.svg) |
| PG·간편결제·지급 대역 | [payment-providers.html](payment-providers.html) | [payment-providers.svg](payment-providers.svg) |
| 관측과 부하 도구 | [observability.html](observability.html) | [observability.svg](observability.svg) |
| 계정·상품 ERD | [erd-accounts.html](erd-accounts.html) | [erd-accounts.svg](erd-accounts.svg) |
| 주문·선착순 이벤트 ERD | [erd-orders.html](erd-orders.html) | [erd-orders.svg](erd-orders.svg) |
| 쿠폰 ERD | [erd-coupons.html](erd-coupons.html) | [erd-coupons.svg](erd-coupons.svg) |
| 결제·마일리지 ERD | [erd-payment.html](erd-payment.html) | [erd-payment.svg](erd-payment.svg) |
| 정산·지급 ERD | [erd-settlement.html](erd-settlement.html) | [erd-settlement.svg](erd-settlement.svg) |
| 금융 Mock ERD | [erd-finance.html](erd-finance.html) | [erd-finance.svg](erd-finance.svg) |
| 메시지·멱등성 ERD | [erd-integration.html](erd-integration.html) | [erd-integration.svg](erd-integration.svg) |

ERD의 전체 컬럼·타입·제약·인덱스는 [데이터 모델](../data-model.md)이 소유합니다. 그림의 `→`와 관계선은 논리 참조이며 DB FK constraint를 의미하지 않습니다. 메시지 뷰의 Outbox→Inbox는 발신/소비 관계이고 각 서비스 DB에 별도로 저장합니다.

## 로고 출처

기술 로고는 각 제품을 식별하기 위한 문서용 표시이며 해당 상표의 소유권은 각 소유자에게 있습니다. 임의의 PG 회사 로고는 사용하지 않습니다.

- [Simple Icons](https://github.com/simple-icons/simple-icons/tree/90646e4bf7f3b89df6c37e65baf3e7bc759a517c) — CC0. Spring Boot, Apache Kafka, k6, Prometheus, Grafana, Docker, PostgreSQL, Redis.
- [OpenZipkin](https://github.com/openzipkin/openzipkin.github.io/blob/master/public/img/logo_svg/zipkin_symbol.svg) — 공식 Zipkin 심볼.
- [Provectus Kafka UI v0.7.2](https://github.com/provectus/kafka-ui/blob/v0.7.2/documentation/images/kafka-ui-logo.png) — 프로젝트 로고, Apache-2.0 저장소.
- 브라우저와 모의 금융 API는 제품 로고가 없는 일반 도형입니다.

HTML은 Google Fonts의 Geist·Geist Mono를 사용합니다. 외부 폰트를 불러오지 않는 SVG 뷰어에서는 시스템 글꼴로 대체됩니다.
