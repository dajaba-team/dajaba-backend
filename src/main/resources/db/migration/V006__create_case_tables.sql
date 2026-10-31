-- V006: cases — 조사 케이스, 메모·상태 이력, AI 조사 요약.
-- V005의 ops.alerts(id bigint)가 먼저 있어야 한다.
-- 번호는 nextval('ops.case_no_seq') + DisplayNumber.caseNo(경보 기준 연도, seq)로 발급한다.
create sequence ops.case_no_seq as bigint start with 1 increment by 1 no cycle;

create table ops.cases (
    id                    bigint generated always as identity primary key,
    case_no               varchar(32) not null unique,
    alert_id              bigint not null unique references ops.alerts(id),
    status                varchar(32) not null default 'OPEN'
                          check (status in ('OPEN', 'REVIEWING', 'ESCALATED', 'DRAFTED',
                                            'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CLOSED')),
    assignee_id           bigint references ops.users(id),
    current_draft_id      bigint, -- 순환 참조: V008에서 ops.str_drafts(id) FK 추가
    rejection_reason_code varchar(32)
                          check (rejection_reason_code in ('NEED_REVISION', 'NOT_REPORTABLE')),
    close_reason          varchar(32)
                          check (close_reason in ('FALSE_POSITIVE', 'DUPLICATE', 'NO_ACTION')),
    version               bigint not null default 0 check (version >= 0),
    created_at            timestamptz not null default now(),
    created_by            bigint not null references ops.users(id),
    updated_at            timestamptz not null default now(),
    updated_by            bigint not null references ops.users(id),
    -- 반려 코드는 현재 REJECTED 분기 값이다. 과거 코드는 case_history·approvals에 남는다.
    constraint cases_rejection_reason_ck check (
        (status = 'REJECTED') = (rejection_reason_code is not null)
    )
);

-- 전체 목록, 상태별 목록, 담당자별 목록. 동일 시각에도 id로 순서를 고정한다.
create index cases_created_idx on ops.cases (created_at desc, id desc);
create index cases_status_created_idx on ops.cases (status, created_at desc, id desc);
create index cases_assignee_created_idx on ops.cases (assignee_id, created_at desc, id desc);
create index cases_current_draft_idx on ops.cases (current_draft_id) where current_draft_id is not null;

create table ops.case_history (
    id              bigint generated always as identity primary key,
    case_id         bigint not null references ops.cases(id),
    action          varchar(32) not null
                    check (action in ('OPEN', 'ASSIGN', 'REASSIGN', 'NOTE', 'ESCALATE', 'CLOSE',
                                      'ATTACH_DRAFT', 'REOPEN_DRAFT', 'SUBMIT_FOR_APPROVAL', 'APPROVE', 'REJECT')),
    from_status     varchar(32)
                    check (from_status in ('OPEN', 'REVIEWING', 'ESCALATED', 'DRAFTED',
                                           'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CLOSED')),
    to_status       varchar(32) not null
                    check (to_status in ('OPEN', 'REVIEWING', 'ESCALATED', 'DRAFTED',
                                         'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CLOSED')),
    -- REJECT의 반려 코드, CLOSE의 종결 사유.
    reason_code     varchar(32)
                    check (reason_code in ('NEED_REVISION', 'NOT_REPORTABLE',
                                           'FALSE_POSITIVE', 'DUPLICATE', 'NO_ACTION')),
    comment         text,
    -- 이 행을 남긴 변경 직전의 cases.version. 같은 시각의 행도 이 값과 id로 순서를 복원한다.
    case_version_before bigint not null check (case_version_before >= 0),
    -- NOTE에는 변경 후 전문을 저장한다. 빈 문자열은 명시적으로 메모를 비운 기록이다.
    investigator_notes text,
    created_at      timestamptz not null default now(),
    created_by      bigint not null references ops.users(id),
    constraint case_history_note_ck check (
        (action = 'NOTE' and investigator_notes is not null)
        or (action <> 'NOTE' and investigator_notes is null)
    )
);
create index case_history_case_created_idx on ops.case_history (case_id, created_at desc, id desc);
create index case_history_note_idx on ops.case_history (case_id, created_at desc, id desc)
    where action = 'NOTE';
-- 같은 케이스 version에서 메모가 두 번 저장되지 않게 한다(@Version 확인의 DB 쪽 방어선).
create unique index case_history_note_version_uk on ops.case_history (case_id, case_version_before)
    where action = 'NOTE';

create table ops.case_summaries (
    id                bigint generated always as identity primary key,
    case_id           bigint not null references ops.cases(id),
    generator_version varchar(64) not null,
    produced_at       timestamptz not null,
    -- 실제 AI 요청(case_no·alert·evidence_transaction_ids) 스냅샷과 그 SHA-256.
    -- is_current는 현재 입력 해시와 비교해 계산한다. 메모·이전 경보는 입력이 아니다.
    input_snapshot    jsonb not null check (jsonb_typeof(input_snapshot) = 'object'),
    input_sha256      varchar(64) not null check (input_sha256 ~ '^[0-9a-f]{64}$'),
    -- AI 호출 전 읽은 cases.version. 동시성 확인용이며 최신성 판정에 쓰지 않는다.
    based_on_case_version bigint not null check (based_on_case_version >= 0),
    -- AI 응답 원문(facts·summary_lines·model_focus·code_checks·generation_meta). 구조는 규약 v0.2 §6.1.
    payload           jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at        timestamptz not null default now(),
    created_by        bigint not null references ops.users(id)
);
-- 같은 입력의 재생성도 새 이력이다. (case_id, input_sha256)에 UNIQUE를 걸지 않는다.
create index case_summaries_case_created_idx on ops.case_summaries (case_id, created_at desc, id desc);

-- V001의 default privileges에서 받은 UPDATE·DELETE를 추가 전용 테이블에서는 회수한다.
revoke update, delete on ops.case_history, ops.case_summaries from app_backend;
