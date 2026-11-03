// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { operations } from '../../shared/api/contract';
import { OperationForm } from './OperationForm';

afterEach(cleanup);
it('다른 상품의 버전을 이벤트 수정의 버전으로 사용하지 않는다', () => {
  const operation = operations.find(op => op.path === '/api/sellers/me/events/{eventId}' && op.method === 'PUT')!;
  render(<OperationForm operation={operation} context={{ productIdVersion: 7, eventId: 'event-1', eventIdVersion: 3 }}
    pending={false} allowed run={() => {}} />);
  expect((screen.getByLabelText(/확인한 버전/) as HTMLInputElement).value).toBe('3');
});
it('동일 작업을 다시 제출할 때 멱등키와 본문을 보존한다', () => {
  const run = vi.fn();
  const operation = operations.find(op => op.path === '/api/orders' && op.method === 'POST')!;
  render(<OperationForm operation={operation} context={{ productId: 'product-1', expectedProductVersion: 3 }}
    pending={false} allowed run={run} />);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0]).toEqual(run.mock.calls[1][0]);
  expect(run.mock.calls[0][0].idempotencyKey.length).toBeGreaterThanOrEqual(8);
});
