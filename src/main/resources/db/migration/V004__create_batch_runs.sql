-- V004: batch_runs (레퍼런스 3.2, 11.1). 코드 소속은 shared/batch (10/22 안건 14).
-- 네 배치(적재·룰 스캔·점수·케이스 생성)가 같은 테이블을 쓴다. created_at 만.

create table ops.batch_runs (
    id             bigint       generated always as identity primary key,
    batch_type     varchar(32)  not null check (batch_type in ('LOAD_DATASET','RULE_SCAN','SCORE_REQUEST','OPEN_CASES')),
    batch_date     date         not null,          -- 대상 일자. LOAD_DATASET 은 적재일
    status         varchar(16)  not null check (status in ('RUNNING','SUCCEEDED','FAILED')),
    started_at     timestamptz  not null default now(),
    finished_at    timestamptz,
    rows_in        int,
    rows_out       int,
    error_message  text,
    triggered_by   bigint       not null references ops.users(id),
    created_at     timestamptz  not null default now()
);

-- 같은 날짜·유형은 성공 후 다시 못 돌린다. 실패한 날은 새 행으로 다시 돌린다 (11.1).
-- ※ 10/27 안건 21: 점수·케이스 생성은 "남은 대상만" 처리하므로 재실행 허용으로 바뀔 수 있다 → 그때 V0xx 에서 이 인덱스를 LOAD_DATASET·RULE_SCAN 으로 좁힌다.
create unique index batch_runs_succeeded_uq
    on ops.batch_runs (batch_type, batch_date) where status = 'SUCCEEDED';
create index batch_runs_type_date_idx on ops.batch_runs (batch_type, batch_date, started_at desc);
