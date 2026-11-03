-- V006: cases — cases, case_history, case_summaries, case_no_seq (레퍼런스 3.3, CASES 명세 §2·§8.1, SCRUM-48).
-- V005(ops.alerts)가 먼저 있어야 한다. 머지 순서: … → V005 → V005_1 → V005_2 → V006 → V008 (05_flyway §3).
-- cases.current_draft_id 의 FK 는 str_drafts 를 만드는 V008 이 건다 (05_flyway §1 순환 참조, #28 cases_current_draft_fk).

-- 케이스 번호 CASE-{경보 기준 연도}-{6자리}. 연도별로 리셋하지 않는다 (레퍼런스 8.7, DisplayNumber.caseNo).
create sequence ops.case_no_seq as bigint start with 1 increment by 1 no cycle;

create table ops.cases (
    id                     bigint       generated always as identity primary key,
    case_no                varchar(32)  not null unique,
    alert_id               bigint       not null unique references ops.alerts(id),   -- 경보 하나당 케이스 최대 하나
    account_key            varchar(64)  not null,             -- 경보의 계좌. accounts FK 는 걸지 않는다 (V005 alerts 와 같음)
    entity_id              varchar(32),                       -- 법인 미확인이면 null
    assignee_id            bigint       references ops.users(id),
    status                 varchar(32)  not null check (status in (
        'OPEN','REVIEWING','ESCALATED','DRAFTED','PENDING_APPROVAL','APPROVED','REPORTED','REJECTED','CLOSED')),
    rejection_reason_code  varchar(32)  check (rejection_reason_code in ('NEED_REVISION','NOT_REPORTABLE')),
    current_draft_id       bigint,                            -- FK 는 V008 (cases_current_draft_fk)
    close_reason           varchar(32)  check (close_reason in ('FALSE_POSITIVE','DUPLICATE','NO_ACTION')),
    version                bigint       not null default 0 check (version >= 0),   -- @Version 낙관적 락
    created_at             timestamptz  not null default now(),
    created_by             bigint       not null references ops.users(id),
    updated_at             timestamptz  not null default now(),
    updated_by             bigint       not null references ops.users(id),
    constraint cases_case_no_ck check (case_no ~ '^CASE-[0-9]{4}-[0-9]{6,}$'),
    -- 반려 코드는 REJECTED 일 때만, REJECTED 면 반드시 (CASES 명세 §2.1·§8.1)
    constraint cases_rejection_code_ck check ((status = 'REJECTED') = (rejection_reason_code is not null)),
    -- 종결 사유는 CLOSED 일 때만, CLOSED 면 반드시. CLOSED 는 끝 상태라 사유가 지워질 일이 없다
    constraint cases_close_reason_ck check ((status = 'CLOSED') = (close_reason is not null)),
    -- 담당자는 OPEN 에만 없다. 재배정은 바꾸기만 하고 비우지 않는다
    constraint cases_assignee_ck check ((status = 'OPEN') = (assignee_id is null)),
    -- 초안은 상신 전에는 없고, DRAFTED 부터 REJECTED 까지는 반드시 있다. ESCALATED·CLOSED 는 둘 다 가능
    constraint cases_current_draft_ck check (
        (status not in ('OPEN','REVIEWING') or current_draft_id is null)
        and (status not in ('DRAFTED','PENDING_APPROVAL','APPROVED','REPORTED','REJECTED') or current_draft_id is not null))
);
-- 목록(상태 필터 + 생성일 정렬), 내 케이스(assignee=me). 같은 시각은 id 로 순서를 고정한다.
create index cases_status_created_idx on ops.cases (status, created_at desc, id desc);
create index cases_assignee_status_idx on ops.cases (assignee_id, status);

create table ops.case_history (
    id                   bigint       generated always as identity primary key,
    case_id              bigint       not null references ops.cases(id),
    action               varchar(32)  not null check (action in (
        'OPEN','ASSIGN','REASSIGN','NOTE','ESCALATE','CLOSE',
        'ATTACH_DRAFT','REOPEN_DRAFT','SUBMIT_FOR_APPROVAL','APPROVE','REJECT')),   -- CaseHistoryAction 과 같은 목록
    from_status          varchar(32)  check (from_status in (
        'OPEN','REVIEWING','ESCALATED','DRAFTED','PENDING_APPROVAL','APPROVED','REPORTED','REJECTED','CLOSED')),
    to_status            varchar(32)  not null check (to_status in (
        'OPEN','REVIEWING','ESCALATED','DRAFTED','PENDING_APPROVAL','APPROVED','REPORTED','REJECTED','CLOSED')),
    reason_code          varchar(32),                         -- REJECT 의 반려 코드, CLOSE 의 종결 사유
    comment              text         check (char_length(comment) <= 10000),   -- NOTE 는 메모 전문 (API-05 최대 10,000자)
    case_version_before  bigint       not null check (case_version_before >= 0),   -- 이 변경 직전의 cases.version
    created_at           timestamptz  not null default now(),
    created_by           bigint       not null references ops.users(id),         -- 업무상 행위자 (생성 배치는 SYSTEM)
    -- 생성(OPEN) 행만 이전 상태가 없다
    constraint case_history_from_status_ck check ((action = 'OPEN') = (from_status is null)),
    -- NOTE 는 메모 전문을 매번 저장한다. 빈 문자열은 "비움" 기록이라 허용, null 은 안 된다 (안건 16)
    constraint case_history_note_ck check (action <> 'NOTE' or comment is not null),
    -- case 로 나눠 NULL 이 섞여도 결과가 true/false 로만 나오게 한다 (OR 로 묶으면 NULL 이 CHECK 를 통과한다)
    constraint case_history_reason_code_ck check (case
        when action = 'REJECT' then reason_code is not null and reason_code in ('NEED_REVISION','NOT_REPORTABLE')
        when action = 'CLOSE'  then reason_code is not null and reason_code in ('FALSE_POSITIVE','DUPLICATE','NO_ACTION')
        else reason_code is null end)
);
-- 케이스 이력 화면(API-11·15): 케이스별·종류별 최신순.
create index case_history_case_action_idx on ops.case_history (case_id, action, created_at desc, id desc);
-- 같은 version 에서 메모가 두 번 저장되지 않게 (CASES 명세 §8.1, @Version 의 DB 쪽 방어선).
create unique index case_history_note_version_uq on ops.case_history (case_id, case_version_before) where action = 'NOTE';

create table ops.case_summaries (
    id                     bigint       generated always as identity primary key,
    case_id                bigint       not null references ops.cases(id),
    payload                jsonb        not null check (jsonb_typeof(payload) = 'object'),          -- AI 응답 원문(검증 통과분)
    input_snapshot         jsonb        not null check (jsonb_typeof(input_snapshot) = 'object'),   -- 실제 보낸 case_no·alert·evidence_transaction_ids
    input_sha256           char(64)     not null check (input_sha256 ~ '^[0-9a-f]{64}$'),           -- is_current 판정용
    generator_version      varchar(64)  not null,             -- generation_meta.prompt_version (03_shared §5, str_drafts 와 같은 이름)
    produced_at            timestamptz  not null,             -- AI 가 만든 시각 (UTC, 미결 27)
    based_on_case_version  bigint       not null check (based_on_case_version >= 0),   -- 호출 전 cases.version. 최신성 판정에 쓰지 않는다
    created_at             timestamptz  not null default now(),
    created_by             bigint       not null references ops.users(id)
);
-- 현재 요약 = 케이스별 최신 1건 (API-13). 같은 입력의 재생성도 새 행이라 (case_id, input_sha256) UNIQUE 는 없다.
create index case_summaries_case_created_idx on ops.case_summaries (case_id, created_at desc, id desc);

-- 케이스는 지우지 않는다(종결도 상태). 이력·요약은 추가만 (01_code_rules 원칙 3).
revoke delete on ops.cases from app_backend;
revoke update, delete on ops.case_history, ops.case_summaries from app_backend;
