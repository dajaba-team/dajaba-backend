#!/bin/bash
# PostgreSQL 컨테이너 첫 기동 때 한 번만 실행된다(볼륨이 비어 있을 때).
# 역할 3개와 스키마 2개를 만든다. 테이블은 Flyway(V001~)가 app_migrate 계정으로 만든다.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    create role app_migrate login password '${APP_MIGRATE_PASSWORD}';
    create role app_backend login password '${APP_BACKEND_PASSWORD}';
    create role app_ai      login password '${APP_AI_PASSWORD}';

    create schema ops authorization app_migrate;
    create schema ai  authorization app_ai;

    grant usage on schema ops to app_backend, app_ai;
    grant usage on schema ai  to app_ai;

    -- app_migrate 가 앞으로 ops 에 만드는 테이블·시퀀스에 app_backend 권한 자동 부여
    alter default privileges for role app_migrate in schema ops
        grant select, insert, update, delete on tables to app_backend;
    alter default privileges for role app_migrate in schema ops
        grant usage, select on sequences to app_backend;
SQL
