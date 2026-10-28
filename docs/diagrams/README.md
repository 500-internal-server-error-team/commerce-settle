# 다이어그램

GitHub 문서에서 SVG 미리보기를 사용합니다. SVG 자체가 편집 원본이며 별도 HTML을 보관하지 않습니다. 로고는 SVG에 포함되어 별도 요청이 필요하지 않습니다.

| 도식 | 미리보기 |
| --- | --- |
| 통합 인프라 | [infrastructure.svg](infrastructure.svg) |
| 서비스 설계 | [services.svg](services.svg) |
| 애플리케이션과 인프라 | [runtime.svg](runtime.svg) |
| 배치·Consumer·지급 Worker 실행 | [background-execution.svg](background-execution.svg) |
| PG·간편결제·지급 대역 | [payment-providers.svg](payment-providers.svg) |
| 관측과 운영 알람 | [observability.svg](observability.svg) |
| 거래 알림 | [notifications.svg](notifications.svg) |
| 계정·상품 ERD | [erd-accounts.svg](erd-accounts.svg) |
| 주문·선착순 이벤트 ERD | [erd-orders.svg](erd-orders.svg) |
| 쿠폰 ERD | [erd-coupons.svg](erd-coupons.svg) |
| 결제·마일리지 ERD | [erd-payment.svg](erd-payment.svg) |
| 정산·지급 ERD | [erd-settlement.svg](erd-settlement.svg) |
| 금융 Mock ERD | [erd-finance.svg](erd-finance.svg) |
| 메시지·멱등성·감사 ERD | [erd-integration.svg](erd-integration.svg) |
| 거래 알림 ERD | [erd-notifications.svg](erd-notifications.svg) |

흰 캔버스·흰 노드와 회청색 테두리를 사용합니다. 의미 구분은 경계·선·글자 위계로 표현하며 제품 로고만 고유색을 유지합니다. 선 라벨은 연결선에서 8px 떨어뜨리고 박스·그룹 경계를 덮지 않습니다.

구조도 노드명은 13px, 보조 설명은 10px, ERD 필드는 10.5px입니다. 긴 라벨은 짧게 쓰고 상세 계약은 본문에 둡니다.

로고와 글자 블록의 중심을 맞추고 전체 내용을 카드 세로 중앙에 둡니다. ERD는 제목부와 필드 영역을 나눠 위아래 여백을 맞추고 행 기준을 유지합니다.

전체 컬럼·타입·제약·인덱스는 [데이터 모델](../data-model.md)이 소유합니다. 관계선은 논리 참조이며 물리 FK가 아닙니다. 구조도는 연동을 완성한 목표 구성이며 구현 상태는 본문에 따로 설명합니다. 노드는 실선 테두리로 구분하고 연결선은 박스 경계에 붙입니다.

## 로고 출처

기술 로고는 각 제품을 식별하기 위한 문서용 표시이며 해당 상표의 소유권은 각 소유자에게 있습니다. 임의의 PG 회사 로고는 사용하지 않습니다.

- [Simple Icons](https://github.com/simple-icons/simple-icons/tree/90646e4bf7f3b89df6c37e65baf3e7bc759a517c) — CC0. Spring Boot, Apache Kafka, k6, Prometheus, Grafana, Docker, PostgreSQL, Redis.
- [Spring Batch 공식 심볼](https://spring.io/img/projects/spring-batch.svg) — Spring 프로젝트를 식별하는 표시.
- [OpenZipkin](https://github.com/openzipkin/openzipkin.github.io/blob/master/public/img/logo_svg/zipkin_symbol.svg) — 공식 Zipkin 심볼.
- [Provectus Kafka UI v0.7.2](https://github.com/provectus/kafka-ui/blob/v0.7.2/documentation/images/kafka-ui-logo.png) — 프로젝트 로고, Apache-2.0 저장소.
- [Slack 공식 로고](https://a.slack-edge.com/9cc0056/marketing/img/nav/logo.svg) — [공식 미디어 키트](https://slack.com/media-kit).
- Alertmanager에는 Prometheus 프로젝트 로고를 사용합니다. 브라우저·모의 API·수집 후보는 일반 도형입니다.

글꼴은 Geist·Geist Mono이며 SVG 뷰어에서는 시스템 글꼴로 대체됩니다.
