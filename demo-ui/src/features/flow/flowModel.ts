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


export const loginStages = [
  ['인증 준비 확인', 'Commerce가 승인된 Redis 실행 식별자와 연결 상태를 확인합니다. 인증 게이트가 닫혀 있으면 503으로 거절합니다.'],
  ['계정·비밀번호 확인', 'Redis에서 로그인 시도 제한을 확인하고, commerce DB에서 계정 상태와 비밀번호 해시를 대조합니다.'],
  ['세션 발급', 'Commerce가 DB 인증 버전과 Redis 가드를 맞추고 토큰 해시·계정·고정 만료를 Redis에 저장합니다.'],
  ['브라우저에서 보관', '응답의 세션 토큰을 현재 탭 메모리에만 보관합니다. 회원가입은 계정 생성이며 로그인은 별도로 실행합니다.'],
];
export const protectedStages = [
  ['인증 헤더 전송', '브라우저가 보관한 세션을 Authorization 헤더로 보냅니다. 화면은 원문 토큰을 표시하지 않습니다.'],
  ['Gateway에서 검증', 'Gateway가 승인한 Redis 실행 식별자와 세대, 토큰 해시·만료·계정 가드를 확인합니다. 미인증은 401, 확인 불가는 503입니다.'],
  ['소유 서비스로 전달', '검증한 계정 문맥과 내부 자격을 소유 서비스로 전달합니다. 서비스는 역할·소유권·업무 상태를 다시 검사합니다.'],
  ['업무 응답 수신', '서비스가 필요한 DB·캐시·내부 API를 사용해 응답합니다. 이 도식은 내부 실행을 관측한 기록이 아닙니다.'],
];
export function authenticationRoute(operation: Operation, protectedRequest = false): FlowNode[] {
  const prefix = protectedRequest ? 'protected' : 'login';
  const owner = protectedRequest ? operation.owner : 'commerce';
  return [
    { id: `${prefix}-ui`, title: '브라우저', subtitle: protectedRequest ? 'Authorization 헤더' : '이메일 · 비밀번호', detail: protectedRequest ? protectedStages[0][1] : '현재 UI의 Vite 개발 프록시 또는 Demo Control 중계를 통해 Gateway로 요청합니다. 응답 토큰은 현재 탭 메모리에만 보관합니다.' },
    { id: `${prefix}-gateway`, title: 'Gateway', subtitle: protectedRequest ? '세션 · 가드 검증' : '공개 로그인 경로', detail: protectedRequest ? protectedStages[1][1] : '구매자·판매자 로그인은 공개 경로입니다. 기존 세션 검증을 거치지 않고 Commerce로 전달하며, 세션 발급은 Commerce가 담당합니다.' },
    { id: `${prefix}-service`, title: application(owner), subtitle: protectedRequest ? '주체 · 권한 · 업무 검사' : '인증 · 세션 발급', detail: protectedRequest ? protectedStages[2][1] : 'Redis 인증 게이트와 로그인 시도 제한을 먼저 확인한 뒤 PostgreSQL 계정·비밀번호를 대조하고 Redis 세션을 발급합니다.' },
    { id: `${prefix}-db`, title: 'PostgreSQL', subtitle: `${database(owner)} DB`, detail: protectedRequest ? '소유 서비스의 업무 DB 예시입니다. 실제 DB 접근·캐시·내부 호출은 선택한 작업의 구현과 조건에 따라 달라집니다.' : 'Commerce가 계정·비밀번호 해시·인증 버전과 로그인 가능한 계정 상태를 확인합니다. 비밀번호 원문을 응답이나 실행 기록에 표시하지 않습니다.' },
    { id: `${prefix}-redis`, title: 'Redis', subtitle: protectedRequest ? '토큰 해시 · 세대 · 만료' : 'gate · 제한 · 세션 저장', detail: protectedRequest ? 'Gateway가 세션과 계정 가드를 같은 검증 스크립트에서 확인합니다. 실행 식별자·연결 상태를 확인하지 못하면 사용자 요청을 차단합니다.' : 'Commerce가 인증 게이트·로그인 시도 제한·계정 가드와 세션 저장에 사용합니다. 승인된 실행 식별자 불일치나 단절 후에는 정식 인증 복구가 필요합니다.' },
  ];
}
