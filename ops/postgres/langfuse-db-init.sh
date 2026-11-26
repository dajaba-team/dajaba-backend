#!/bin/sh
set -eu

export PGPASSWORD="$POSTGRES_PASSWORD"
export PGOPTIONS='-c timezone=UTC'
# 비밀번호가 SQL 오류에도 남지 않도록 출력을 막는다.
if ! psql -X -q -h "${PGHOST:-db}" -U postgres -d aml -v ON_ERROR_STOP=1 \
    -v pw="$LANGFUSE_DB_PASSWORD" >/dev/null 2>&1 <<'SQL'
SELECT 'CREATE ROLE langfuse LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'langfuse')
\gexec
ALTER ROLE langfuse PASSWORD :'pw';
-- RDS 처럼 관리자가 superuser 가 아니면, 아래 OWNER 지정에 역할 멤버십이 필요하다(로컬 superuser 는 건너뜀)
SELECT 'GRANT langfuse TO CURRENT_USER'
WHERE NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
\gexec
SELECT 'CREATE DATABASE langfuse OWNER langfuse'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langfuse')
\gexec
ALTER DATABASE langfuse SET timezone TO 'UTC';
SQL
then
    printf '%s\n' 'langfuse DB 준비 실패 — db 접속(POSTGRES_PASSWORD)·권한 확인' >&2
    exit 1
fi
printf '%s\n' 'langfuse DB 준비 끝'
