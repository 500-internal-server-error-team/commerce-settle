FROM postgres:18.3
COPY infra/postgres/01-init.sh /docker-entrypoint-initdb.d/01-init.sh
RUN chmod 0644 /docker-entrypoint-initdb.d/01-init.sh
