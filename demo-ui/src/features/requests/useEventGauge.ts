import { useQuery } from '@tanstack/react-query';
import { execute, type Execution, type RequestValues } from '../../shared/api/client';
import { redact, type Operation } from '../../shared/api/contract';

export function useEventGauge(operation: Operation | undefined, record: Execution | undefined,
  actorKey: (string | number)[], token?: string, onUnauthorized?: (token: string) => void): { current?: Execution; error: boolean } {
  const enabled = !!operation?.path.endsWith('/gauge') && !!record
    && record.path === operation.path && record.status >= 200 && record.status < 300
    && (operation.actor === 'PUBLIC' || operation.actor === 'OPERATOR' || !!token);
  const query = useQuery({
    queryKey: ['actor', ...actorKey, 'live-gauge', record?.id],
    enabled,
    queryFn: ({ signal }) => execute(operation!, record!.request as RequestValues, token, signal, onUnauthorized),
    refetchInterval: 1000, refetchIntervalInBackground: false, retry: false,
  });
  if (!enabled || !record || !query.data) return { current: record, error: enabled && query.isError };
  return { current: { ...record, status: query.data.status, response: redact(query.data.data),
    elapsedMs: query.data.elapsedMs, at: new Date(query.dataUpdatedAt).toISOString() }, error: query.isError };
}
