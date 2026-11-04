import spec from '../../../../docs/openapi.json';
import type { Operation } from '../../shared/api/contract';

export const eventContracts = spec['x-event-contracts'];
export interface FlowNode { id: string; title: string; subtitle: string; detail: string }
export const database = (owner: string) => owner === 'finance-mock' ? 'finance_mock' : owner;
export const application = (owner: string) => ({ commerce: 'Commerce', payment: 'Payment API',
  settlement: 'Settlement', 'finance-mock': 'Finance Mock' })[owner] ?? owner;
export function requestRoute(operation: Operation, development: boolean): FlowNode[] {
  const operator = operation.actor === 'OPERATOR';
  return [
    { id: 'ui', title: '데모 UI', subtitle: '브라우저', detail: '요청과 응답을 관측하는 지점입니다. 표시 시간은 브라우저에서 응답 본문을 확인하기까지의 왕복 시간입니다.' },
    { id: 'proxy', title: development ? 'Vite' : 'Demo Control', subtitle: development ? '개발 프록시 · :5173' : 'Java 중계 · :4174',
      detail: development ? operator ? '운영 요청은 /demo/requests를 통해 Demo Control로 전달합니다.' : '업무 요청의 메서드·경로·본문을 Gateway로 전달합니다.' : '사용자 요청은 Gateway로, 운영 요청은 설정된 소유 서비스로 전달합니다.' },
    ...(operator ? development ? [{ id: 'control', title: 'Demo Control', subtitle: '운영 요청 중계 · :4174', detail: '서버에 설정한 대상별 운영 자격으로 소유 서비스를 호출합니다. Gateway를 거치지 않습니다.' }] : []
      : [{ id: 'gateway', title: 'Gateway', subtitle: 'Spring Boot · :8080', detail: '공개 API를 소유 서비스로 라우팅합니다. 보호 요청은 Redis 세션을 검증하며 인증·요청 제한에서 거절되면 서비스에 도달하지 않을 수 있습니다. DB는 직접 읽지 않습니다.' }]),
    { id: 'service', title: application(operation.owner), subtitle: 'Spring Boot', detail: `${application(operation.owner)}가 이 API를 소유합니다. 내부 HTTP 호출·캐시·조건 분기가 있을 수 있으며, 이 그림은 모든 내부 호출의 실행 추적이 아닙니다.` },
    { id: 'db', title: 'PostgreSQL', subtitle: `${database(operation.owner)} DB`, detail: 'PostgreSQL 컨테이너 1대 안에서 commerce·payment·settlement·finance_mock DB와 계정을 분리합니다. Payment API와 Worker는 payment DB를 공유합니다. 조회 캐시·인증·검증 분기에 따라 DB에 접근하지 않을 수 있습니다.' },
  ];
}

/** Consumer group names are not always process names. Payment events execute in the Worker. */
export function consumerApplication(consumer: string): string {
  return consumer === 'payment' ? 'Payment Worker' : consumer === 'commerce-notifications' ? 'Commerce · 알림' : application(consumer);
}
