-- V007: ai_call_logs (레퍼런스 3.4). 코드 소속은 shared/ai (SCRUM-18).
-- HTTP 시도마다 메타데이터만 기록한다. 본문·FK·created_by 없이 created_at 만 (10/29 결정).

create table ops.ai_call_logs (
    id            bigint generated always as identity primary key,
    endpoint      varchar(64)  not null,          -- 경로는 늘 수 있어 CHECK 없음
    request_id    varchar(64)  not null,          -- 재시도해도 같은 X-Request-Id
    attempt       smallint     not null check (attempt in (1,2)),
    status_code   int,                           -- AI 원래 HTTP 상태. 타임아웃·연결 실패는 null
    error_code    varchar(32),                   -- 코드가 늘 수 있어 CHECK 없음
    duration_ms   int          not null,
    ai_version    varchar(64),
    generation_id varchar(64),
    target_type   varchar(16)  check (target_type in ('BATCH_RUN','CASE','DRAFT')),
    target_id     varchar(64),                   -- caseId·draftId 가 문자라 bigint 대신 varchar
    created_at    timestamptz  not null default now()
);
create index ai_call_logs_created_at_idx on ops.ai_call_logs (created_at);
create index ai_call_logs_endpoint_status_idx on ops.ai_call_logs (endpoint, status_code);
create index ai_call_logs_request_id_idx on ops.ai_call_logs (request_id);
revoke update, delete on ops.ai_call_logs from app_backend;   -- 추가만 (원칙 3)
