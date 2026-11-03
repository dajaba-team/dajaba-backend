-- V005: detection — rules, alerts, alert_transactions, alert_no_seq (레퍼런스 3.2, SCRUM-72).
-- 룰 행은 넣지 않는다. 룰별 조건·유형은 룰 명세(SCRUM-73)에서 정해 새 버전 행으로 추가한다.
-- alert_scores 는 V005_1.

-- 경보 번호 ALT-{배치일}-{6자리}. 날짜별로 리셋하지 않는다 (레퍼런스 8.7).
create sequence ops.alert_no_seq as bigint start with 1 increment by 1 no cycle;

create table ops.rules (
    id             bigint       generated always as identity primary key,
    rule_id        varchar(32)  not null,                  -- 규약 2절 룰 ID (R02_FAN_IN_24H)
    version        int          not null check (version >= 1),
    name           varchar(100) not null,
    typology_hint  varchar(32),                            -- 룰 유형. 원본 pattern_type(정답)과 다른 값이고 목록은 룰 명세에서 정한다
    params         jsonb        not null check (jsonb_typeof(params) = 'object'),
    is_active      boolean      not null default false,
    created_at     timestamptz  not null default now(),
    created_by     bigint       not null references ops.users(id),
    updated_at     timestamptz  not null default now(),
    updated_by     bigint       not null references ops.users(id),
    -- 기준값을 바꾸면 기존 행을 고치지 않고 새 버전 행을 추가한다. 과거 경보가 옛 버전을 가리킨다.
    constraint rules_rule_version_uq unique (rule_id, version)
);
-- 룰 하나에 활성 버전은 하나만.
create unique index rules_active_uq on ops.rules (rule_id) where is_active;

create table ops.alerts (
    id                  bigint       generated always as identity primary key,
    alert_no            varchar(32)  not null unique,
    batch_run_id        bigint       not null references ops.batch_runs(id),
    rule_ref_id         bigint       not null references ops.rules(id),
    account_key         varchar(64)  not null,             -- accounts FK 는 걸지 않는다 (V002와 같은 이유)
    entity_id           varchar(32),
    window_start        timestamp    not null,             -- 데이터 시각이라 시간대 없음 (미결 27)
    window_end          timestamp    not null,
    reference_ts        timestamp    not null,
    rule_facts          jsonb        not null check (jsonb_typeof(rule_facts) = 'object'),
    batch_date          date         not null,             -- batch_runs.batch_date 복사. 목록 필터용
    score_status        varchar(16)  not null default 'PENDING'
                        check (score_status in ('PENDING', 'SCORED', 'FAILED')),
    score_failure_code  varchar(32)
                        check (score_failure_code in ('TRANSACTION_NOT_FOUND', 'HISTORY_NOT_SUPPORTED',
                                                      'REQUIRED_INPUT_MISSING', 'COMPUTE_ERROR')),
    case_opened         boolean      not null default false,
    created_at          timestamptz  not null default now(),
    -- reference_ts 는 창의 끝을 넘지 않는다. R01·R02·R07 은 window_end = reference_ts 다 (B7, 11/2).
    -- 같다고 강제하지는 않는다 — 창 중간에 조건이 성립하는 룰이 있을 수 있고 ML 규약 §2.2·AC27 도 둘을 따로 본다.
    constraint alerts_window_ck check (window_start < window_end and window_start < reference_ts
                                       and reference_ts <= window_end),
    -- 실패 코드는 FAILED 일 때만. FAILED 이면서 코드가 없는 경우는 묶음 실패·응답 누락이다 (미결 16).
    constraint alerts_failure_code_ck check (score_failure_code is null or score_status = 'FAILED'),
    constraint alerts_rule_account_window_uq unique (rule_ref_id, account_key, window_start)
);
-- 점수 요청·케이스 생성 대상, 계좌별 경보, 같은 법인의 과거 경보.
create index alerts_batch_status_idx on ops.alerts (batch_date, score_status);
create index alerts_account_reference_idx on ops.alerts (account_key, reference_ts);
create index alerts_entity_reference_idx on ops.alerts (entity_id, reference_ts);

create table ops.alert_transactions (
    alert_id        bigint not null references ops.alerts(id),
    transaction_id  bigint not null references ops.transactions(transaction_id),
    primary key (alert_id, transaction_id)
);
create index alert_transactions_transaction_idx on ops.alert_transactions (transaction_id);

-- 룰·경보 행은 지우지 않는다. 룰은 기준값을 고치지 않고 새 버전 행을 넣으므로 활성 여부만 바뀐다.
-- 경보는 만든 뒤 점수 상태·실패 코드·케이스 생성 여부만 바뀐다.
-- app_ai 는 원천 4개만 읽으므로 여기 테이블에는 권한을 주지 않는다.
revoke update, delete on ops.rules, ops.alerts, ops.alert_transactions from app_backend;
grant update (is_active, updated_at, updated_by) on ops.rules to app_backend;
grant update (score_status, score_failure_code, case_opened) on ops.alerts to app_backend;
