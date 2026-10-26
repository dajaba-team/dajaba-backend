-- V002: ① 원천 4개 + 적재 스테이징 (레퍼런스 3.1, 11.2). 컬럼 정의는 규약 1절.
-- 네 테이블 모두 created_at 만, updated_* 없음. @Immutable 엔티티.

create table ops.entities (
    entity_id    varchar(32)  primary key,
    entity_name  varchar(200) not null,
    entity_type  varchar(32)  not null,          -- CORPORATION, PARTNERSHIP, SOLE_PROPRIETORSHIP, INDIVIDUAL, COUNTRY, DIRECT
    created_at   timestamptz  not null default now()
);
create index entities_type_idx on ops.entities (entity_type);

create table ops.accounts (
    account_key  varchar(64)  primary key,       -- {bank_id}_{account_no}
    bank_id      varchar(16)  not null,
    bank_name    varchar(200),
    account_no   varchar(32)  not null,
    entity_id    varchar(32)  not null references ops.entities(entity_id),
    created_at   timestamptz  not null default now()
);
create index accounts_entity_idx on ops.accounts (entity_id);
create index accounts_bank_idx   on ops.accounts (bank_id);

create table ops.customer_attributes (
    entity_id        varchar(32) primary key references ops.entities(entity_id),
    industry         varchar(32) not null,       -- RETAIL, WHOLESALE, MANUFACTURING, SERVICE, FINANCE, REAL_ESTATE, CONSTRUCTION, OTHER
    risk_level       varchar(8)  not null check (risk_level in ('LOW','MEDIUM','HIGH')),
    account_purpose  varchar(32) not null,       -- SALES_DEPOSIT, PAYROLL, INVESTMENT, TRADE, PERSONAL, OTHER
    onboarded_at     date        not null,
    country_code     char(2)     not null,
    created_at       timestamptz not null default now()
);

create table ops.transactions (
    transaction_id     bigint        primary key, -- CSV 행 번호. 적재 시 명시 (자동증가 아님)
    ts                 timestamp     not null,     -- IBM 원본, 시간대 없음
    from_bank_id       varchar(16)   not null,
    from_account_no    varchar(32)   not null,
    from_account_key   varchar(64)   not null,
    to_bank_id         varchar(16)   not null,
    to_account_no      varchar(32)   not null,
    to_account_key     varchar(64)   not null,
    amount_received    numeric(18,2) not null,
    currency_received  varchar(32)   not null,
    amount_paid        numeric(18,2) not null,
    currency_paid      varchar(32)   not null,
    payment_format     varchar(32)   not null,    -- Cheque, Credit Card, ACH, Cash, Reinvestment, Wire, Bitcoin
    is_laundering      boolean       not null,    -- 적재만 하고 백엔드 코드는 읽지 않는다 (정답 누수 방지)
    is_self_transfer   boolean       not null,    -- from_account_key = to_account_key
    pattern_type       varchar(32),               -- Patterns.txt 에 있는 거래만. FAN_IN, FAN_OUT, GATHER_SCATTER, SCATTER_GATHER, CYCLE, STACK, BIPARTITE, RANDOM
    pattern_id         varchar(32),
    created_at         timestamptz   not null default now()
);
-- accounts 로의 FK 는 걸지 않는다 (508만 행 적재 시간 + 파일에 없는 계좌 존재). 적재 후 고아 계좌 수를 검증 쿼리로 기록.
create index transactions_to_ts_idx   on ops.transactions (to_account_key, ts);
create index transactions_from_ts_idx on ops.transactions (from_account_key, ts);
create index transactions_ts_idx      on ops.transactions (ts);

-- 적재 스테이징: CSV 원본 11컬럼을 문자열 그대로 COPY 로 받는다. 앱에 DDL 권한이 없으므로 여기서 미리 만든다.
create unlogged table ops.transactions_staging (
    row_no              bigint generated always as identity,
    timestamp_text      text,
    from_bank           text,
    from_account        text,
    to_bank             text,
    to_account          text,
    amount_received     text,
    receiving_currency  text,
    amount_paid         text,
    payment_currency    text,
    payment_format      text,
    is_laundering       text
);

-- 권한: AI 는 원천 4개 SELECT 만. 백엔드는 스테이징 TRUNCATE 가능.
grant select on ops.transactions, ops.accounts, ops.entities, ops.customer_attributes to app_ai;
grant select, insert, update, delete, truncate on ops.transactions_staging to app_backend;
