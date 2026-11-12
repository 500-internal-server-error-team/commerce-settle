#!/usr/bin/env bash
set -euo pipefail

for spec in commerce:COMMERCE_DB_PASSWORD payment:PAYMENT_DB_PASSWORD settlement:SETTLEMENT_DB_PASSWORD finance_mock:FINANCE_MOCK_DB_PASSWORD; do
  service="${spec%%:*}"
  password_key="${spec#*:}"
  psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 \
    --set service="$service" --set password="${!password_key}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service', :'password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'service', :'service') \gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'service') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'service', :'service') \gexec
SQL
  psql --username "$POSTGRES_USER" --dbname "$service" --set ON_ERROR_STOP=1 \
    --single-transaction --file=- --set service="$service" \
    --set schema_file="/opt/commerce-settle/schema/${service}.sql" <<'SQL'
SELECT format('SET ROLE %I', :'service') \gexec
\i :schema_file
SQL
done
psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 <<'SQL'
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
SQL
