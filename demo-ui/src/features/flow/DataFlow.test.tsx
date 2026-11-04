// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { operations, type Operation } from '../../shared/api/contract';
import { journal } from '../../shared/api/client';
import { DataFlow } from './DataFlow';
import { consumerApplication, eventContracts, requestRoute } from './flowModel';

afterEach(() => { cleanup(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); });
const operation = (path: string, method = 'GET') => operations.find(op => op.path === path && op.method === method)!;
const values = { pathParameters: {}, query: {}, body: null, idempotencyKey: '' };

it('uses Gateway for all public/user operations and the owner directly for every operator operation in both transports', () => {
  for (const op of operations) for (const dev of [false, true]) {
    const nodes = requestRoute(op, dev);
    expect(nodes.some(n => n.id === 'gateway')).toBe(op.actor !== 'OPERATOR');
    expect(nodes.find(n => n.id === 'db')?.subtitle).toBe(`${op.owner === 'finance-mock' ? 'finance_mock' : op.owner} DB`);
    expect(nodes.find(n => n.id === 'proxy')?.title).toBe(dev ? 'Vite' : 'Demo Control');
  }
});

it('keeps the journal entry route with its exact values, masks secrets, and never promotes a 202 to internal completion', () => {
  const op = operation('/api/orders', 'POST');
  const record = journal(op, { ...values, body: { password: 'must-not-appear', quantity: 2 } }, 'BUYER',
    { status: 202, data: { orderId: 'saved-order', token: 'must-not-appear' }, elapsedMs: 84 });
  render(<DataFlow operation={operation('/api/products')} record={record} pending={false} />);
  expect(screen.getByText('/api/orders')).toBeTruthy();
  expect(screen.getByText('HTTP 202 · 84ms')).toBeTruthy();
  expect(screen.getByText(/202는 접수 응답이며 최종 완료가 아닙니다/)).toBeTruthy();
  fireEvent.click(screen.getByText('이 응답의 보낸 값·받은 결과'));
  expect(screen.getByText(/saved-order/)).toBeTruthy();
  expect(screen.queryByText(/must-not-appear/)).toBeNull();
  expect(screen.getByText(/실행 추적 아님/)).toBeTruthy();
});

it('allows inspecting every event and each of its consumers without any network request', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  render(<DataFlow operation={operation('/api/products')} pending={false} />);
  for (const event of eventContracts) {
    fireEvent.change(screen.getByLabelText('살펴볼 이벤트'), { target: { value: event.name } });
    expect(screen.getByText(`commerce-settle.${event.producer}.v1`)).toBeTruthy();
    for (const consumer of event.consumers) {
      fireEvent.change(screen.getByLabelText('소비 그룹'), { target: { value: consumer } });
      const svg = screen.getByRole('img', { name: `${event.name}: ${event.producer} Outbox에서 ${consumer} Inbox까지의 설계 경로` });
      expect(within(svg).getByText(consumerApplication(consumer))).toBeTruthy();
    }
  }
  fireEvent.click(screen.getByRole('button', { name: '시간순 왕복' }));
  expect(screen.getByRole('group', { name: '시간순 요청·응답 · 조건부 설계' })).toBeTruthy();
  expect(fetch).not.toHaveBeenCalled();
});

it('shows the Redis authentication branch only for protected user requests and supports keyboard node selection', () => {
  vi.stubEnv('DEV', true);
  const { rerender } = render(<DataFlow operation={operation('/api/products')} pending={false} />);
  expect(screen.queryByRole('button', { name: /Redis/ })).toBeNull();
  rerender(<DataFlow operation={operation('/api/accounts/me')} pending={true} />);
  expect(screen.getByText('응답 확인 중')).toBeTruthy();
  fireEvent.keyDown(screen.getByRole('button', { name: /Redis/ }), { key: 'Enter' });
  expect(screen.getByText(/세션 발급·폐기는 Commerce가 소유합니다/)).toBeTruthy();
  const operator = operations.find(op => op.actor === 'OPERATOR') as Operation;
  rerender(<DataFlow operation={operator} pending={false} />);
  expect(screen.queryByRole('button', { name: /Gateway ·/ })).toBeNull();
  expect(screen.getByRole('button', { name: /Demo Control · 운영 요청 중계/ })).toBeTruthy();
});
