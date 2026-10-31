-- V004_1: 원본 파일 추적, transaction_id 자동 발급, 적재 스테이징 (안건 1, 미결 6, 서식 5-10)
-- 금액 타입은 규약대로 numeric(18,2) 유지. Bitcoin 소수 6자리 문제는 규약 변경 요청으로 별도 결정한다.

create table ops.source_files (
    id          bigint       generated always as identity primary key,
    file_name   varchar(255) not null,
    file_hash   char(64)     not null unique,     -- 원본 파일 바이트의 SHA-256
    created_at  timestamptz  not null default now()
);

-- 적재 전 빈 테이블이므로 NOT NULL 컬럼을 바로 추가할 수 있다.
alter table ops.transactions
    alter column transaction_id add generated always as identity,
    add column source_file_id    bigint      not null references ops.source_files(id),
    add column source_row_number bigint      not null,
    add column from_bank_id_raw  varchar(16) not null,
    add column to_bank_id_raw    varchar(16) not null,
    add constraint transactions_source_row_uq unique (source_file_id, source_row_number);

comment on column ops.transactions.from_bank_id is '앞 0을 제거한 서비스용 은행 ID (미결 6)';
comment on column ops.transactions.to_bank_id is '앞 0을 제거한 서비스용 은행 ID (미결 6)';
comment on column ops.transactions.from_bank_id_raw is 'CSV 원본 은행 ID';
comment on column ops.transactions.to_bank_id_raw is 'CSV 원본 은행 ID';

-- "{국가} Bank #n" 형식만 값이 있다. 거래자 국적이 아니다 (서식 5-10).
alter table ops.accounts
    add column bank_country varchar(64);

create unlogged table ops.accounts_staging (
    bank_name       text,
    bank_id         text,
    account_number  text,
    entity_id       text,
    entity_name     text
);

create unlogged table ops.patterns_staging (
    pattern_id          text,
    pattern_type        text,
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

-- SELECT·INSERT·UPDATE·DELETE는 default privileges로 받으므로 TRUNCATE만 추가한다.
-- source_files는 백엔드 내부 추적용이라 app_ai에 권한을 주지 않는다.
grant truncate on ops.accounts_staging, ops.patterns_staging to app_backend;

-- 적재는 INSERT만 사용한다. 원천 UPDATE·DELETE는 DB에서 막는다.
revoke update, delete on ops.transactions, ops.accounts, ops.entities,
                         ops.customer_attributes, ops.source_files from app_backend;
