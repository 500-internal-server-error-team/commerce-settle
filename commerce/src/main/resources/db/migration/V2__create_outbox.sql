CREATE TABLE commerce.outbox (
    id uuid PRIMARY KEY,
    sequence_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_type varchar(80) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    topic varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL,
    message_key varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    raw_body bytea NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'PUBLISHED', 'REVIEW_REQUIRED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_run_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    published_partition integer CHECK (published_partition >= 0),
    published_offset bigint CHECK (published_offset >= 0),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((status = 'PUBLISHED') = (published_partition IS NOT NULL)),
    CHECK ((published_partition IS NULL AND published_offset IS NULL AND published_at IS NULL)
        OR (published_partition IS NOT NULL AND published_offset IS NOT NULL AND published_at IS NOT NULL)),
    CHECK (payload = convert_from(raw_body, 'UTF8')::jsonb),
    CHECK (jsonb_typeof(payload) = 'object' AND payload ?& ARRAY[
        'eventId','eventType','eventVersion','occurredAt','aggregateId','aggregateVersion','payload']),
    CHECK (jsonb_typeof(payload->'eventId')='string' AND jsonb_typeof(payload->'eventType')='string'
        AND jsonb_typeof(payload->'eventVersion')='number' AND jsonb_typeof(payload->'occurredAt')='string'
        AND jsonb_typeof(payload->'aggregateId')='string' AND jsonb_typeof(payload->'aggregateVersion')='number'
        AND jsonb_typeof(payload->'payload')='object'),
    CHECK ((payload ->> 'eventId')::uuid = id),
    CHECK (payload ->> 'eventType' = event_type),
    CHECK ((payload ->> 'eventVersion')::integer = event_version),
    CHECK ((payload ->> 'aggregateId')::uuid = aggregate_id),
    CHECK ((payload ->> 'aggregateVersion')::bigint = aggregate_version),
    CHECK ((payload ->> 'occurredAt')::timestamptz = occurred_at)
);
CREATE INDEX outbox_due ON commerce.outbox(status, next_run_at, lease_until, id);
CREATE INDEX outbox_occurred ON commerce.outbox(occurred_at, created_at, id);
CREATE INDEX outbox_aggregate ON commerce.outbox(aggregate_id, aggregate_version, sequence_no);
