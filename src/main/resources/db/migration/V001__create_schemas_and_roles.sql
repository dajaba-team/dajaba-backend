-- V001: 스키마·역할 권한 (레퍼런스 원칙 5, 7.3)
-- 역할 3개(app_migrate, app_backend, app_ai)와 스키마 ops·ai 는 db/init/01_roles.sh 가 컨테이너 첫 기동 때 만든다.
-- Flyway 는 app_migrate 로 돌므로 여기서는 권한만 정리한다. 이 파일은 main 에 들어간 뒤 고치지 않는다.

grant usage on schema ops to app_backend, app_ai;

-- ops 안의 테이블은 기본적으로 app_backend 가 읽고 쓴다 (01_roles.sh 의 default privileges 와 같은 내용 — 멱등)
alter default privileges for role app_migrate in schema ops
    grant select, insert, update, delete on tables to app_backend;
alter default privileges for role app_migrate in schema ops
    grant usage, select on sequences to app_backend;

-- app_ai 는 원천 4개만 SELECT (V002 에서 테이블 만들며 grant). 그 외 ops 테이블은 접근 없음.
