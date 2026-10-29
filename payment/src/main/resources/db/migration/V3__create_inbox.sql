CREATE TABLE payment.inbox (
    id uuid PRIMARY KEY,
    consumer varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    raw_body bytea NOT NULL,
    body_hash char(64) NOT NULL,
    source_topic varchar(100) NOT NULL,
    source_partition integer NOT NULL CHECK (source_partition >= 0),
    source_offset bigint NOT NULL CHECK (source_offset >= 0),
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    status varchar(24) NOT NULL CHECK (status IN ('RECEIVED', 'WAITING', 'APPLIED', 'REVIEW_REQUIRED')),
    next_run_at timestamptz,
    result_ref uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (consumer, event_id),
    CHECK (status <> 'WAITING' OR next_run_at IS NOT NULL),
    CHECK (payload = convert_from(raw_body, 'UTF8')::jsonb),
    CHECK (jsonb_typeof(payload) = 'object' AND payload ?& ARRAY[
        'eventId','eventType','eventVersion','occurredAt','aggregateId','aggregateVersion','payload']),
    CHECK (jsonb_typeof(payload->'eventId')='string' AND jsonb_typeof(payload->'eventType')='string'
        AND jsonb_typeof(payload->'eventVersion')='number' AND jsonb_typeof(payload->'occurredAt')='string'
        AND jsonb_typeof(payload->'aggregateId')='string' AND jsonb_typeof(payload->'aggregateVersion')='number'
        AND jsonb_typeof(payload->'payload')='object'),
    CHECK ((payload ->> 'eventId')::uuid = event_id),
    CHECK (payload ->> 'eventType' = event_type),
    CHECK ((payload ->> 'eventVersion')::integer = event_version),
    CHECK ((payload ->> 'aggregateId')::uuid = aggregate_id),
    CHECK ((payload ->> 'aggregateVersion')::bigint = aggregate_version),
    CHECK ((payload ->> 'occurredAt')::timestamptz = occurred_at)
);
CREATE INDEX inbox_state ON payment.inbox(status, created_at, id);
CREATE INDEX inbox_waiting ON payment.inbox(consumer, next_run_at, id) WHERE status = 'WAITING';

CREATE TABLE payment.inbox_conflicts (
    id uuid PRIMARY KEY,
    inbox_id uuid NOT NULL,
    consumer varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    raw_body bytea NOT NULL,
    body_hash char(64) NOT NULL,
    source_topic varchar(100) NOT NULL,
    source_partition integer NOT NULL CHECK (source_partition >= 0),
    source_offset bigint NOT NULL CHECK (source_offset >= 0),
    error_code varchar(80) NOT NULL DEFAULT 'EVENT_BODY_CONFLICT',
    status varchar(24) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status = 'REVIEW_REQUIRED'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (consumer, event_id, source_topic, source_partition, source_offset)
);
CREATE INDEX inbox_conflicts_event ON payment.inbox_conflicts(consumer, event_id, created_at);
