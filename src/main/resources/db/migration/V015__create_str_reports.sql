-- V015: str_reports — 확정 보고서 (서식 회의 10/30 결정 1·2·5-2·5-3, 양식 필드 정의 별지 제1호).
-- 승인(P2) 트랜잭션에서 한 행을 만든다: 문서번호·마감·서식 스냅샷(표제부~VII)·의심 유형·정도를 고정.
-- 모의 제출(P4) 트랜잭션에서 reported_at·late 를 채우고 pdf_status = PENDING.
-- PDF 는 제출 커밋 뒤에 만들고(READY/FAILED), 실패해도 승인·제출은 되돌리지 않는다. 다시 만들기는 승인자(P5).
-- 확정 보고서 정보는 STR 소유 — cases 에 중복 저장하지 않는다(5-2, 지윤). cases 화면 마감 배지는 공개 조회로 준다.
-- 머지 순서: … → V013 → V014 → V015 (05_flyway §3). V008·V009 가 먼저 있어야 한다.

-- 문서번호 STR-{연도}-{6자리} 의 일련번호. 케이스 번호(ops.case_no_seq)처럼 연도가 바뀌어도 이어서 센다.
create sequence ops.str_report_no_seq as bigint start with 1 increment by 1 no cycle;

-- 아래 복합 FK 대상: 그 초안의 승인 기록만 가리키게 한다.
alter table ops.approvals add constraint approvals_draft_id_uq unique (draft_id, id);

create table ops.str_reports (
    id                      bigint generated always as identity primary key,
    draft_id                bigint not null unique,                -- 초안당 확정 보고서 하나 (승인은 초안당 한 번, V009)
    case_id                 bigint not null,
    approval_id             bigint not null unique,                -- 이 보고서를 만든 승인 기록 (decision = APPROVED)
    report_no               varchar(32) not null unique,           -- 표제부 1. DisplayNumber 로 만든다
    -- 1. 승인 이후 상태와 마감
    approved_at             timestamptz not null,                  -- 승인 시각 (UTC Z)
    report_due_at           timestamptz not null,                  -- 승인일 다음 영업일부터 3번째 영업일 23:59 KST, 토·일만 제외. 승인 때 계산해 고정
    reported_at             timestamptz,                           -- 모의 제출 시각 = 표제부 2 보고일자
    reported_by             bigint references ops.users(id),       -- 모의 제출한 승인자
    late                    boolean,                               -- 마감일 다음 날 00:00 KST 이후 제출이면 true (23:59 분 전체 인정). 제출 때 판정해 저장
    -- 5-3. 의심 유형·정도 — 승인 때 str_drafts 에서 복사해 고정
    suspicion_types         varchar(16)[] not null,                -- 서식 VI 유형 번호
    suspicion_level_branch  smallint not null check (suspicion_level_branch between 1 and 5),  -- 지점용, 조사역
    suspicion_level_hq      smallint not null check (suspicion_level_hq between 1 and 5),      -- 본점용, 승인자
    -- 2. 무엇을 승인했나 + PDF 재료. 최신 점수·바뀐 고객 정보로 다시 조립하지 않는다
    narrative_sha256        char(64) not null check (narrative_sha256 ~ '^[0-9a-f]{64}$'),  -- 승인 당시 본문 해시 (approvals 와 같은 값)
    form_snapshot           jsonb not null check (jsonb_typeof(form_snapshot) = 'object'),  -- 표제부~VII + 내부 결재 정보. 보고일자는 제출 때 PDF 에만 넣는다
    snapshot_version        varchar(32) not null,                  -- 스냅샷 모양 버전 (양식 필드 정의 기준)
    -- 2. 확정본 PDF — 파일은 저장소, DB 에는 보관 키·해시·템플릿 버전·상태
    pdf_status              varchar(16)
                            check (pdf_status in ('PENDING', 'READY', 'FAILED')),
    pdf_storage_key         varchar(512),
    pdf_sha256              char(64) check (pdf_sha256 ~ '^[0-9a-f]{64}$'),
    pdf_size_bytes          bigint check (pdf_size_bytes > 0),
    pdf_template_version    varchar(32),
    pdf_generated_at        timestamptz,
    pdf_failure_code        varchar(64),                           -- FAILED 일 때 이유 (본문·계좌 값은 넣지 않는다)
    pdf_attempts            int not null default 0 check (pdf_attempts >= 0),
    version                 bigint not null default 0 check (version >= 0),
    created_at              timestamptz not null default now(),
    created_by              bigint not null references ops.users(id),  -- 승인자
    updated_at              timestamptz not null default now(),
    updated_by              bigint not null references ops.users(id),

    constraint str_reports_report_no_ck check (report_no ~ '^STR-[0-9]{4}-[0-9]{6,}$'),
    constraint str_reports_due_ck check (report_due_at > approved_at),
    -- 그 케이스의 그 초안, 그 초안의 승인 기록
    constraint str_reports_case_draft_fk foreign key (case_id, draft_id)
        references ops.str_drafts (case_id, id),
    constraint str_reports_draft_approval_fk foreign key (draft_id, approval_id)
        references ops.approvals (draft_id, id),
    -- 제출 칸 셋은 함께 있거나 함께 없다
    constraint str_reports_reported_ck check (
        (reported_at is null) = (reported_by is null)
        and (reported_at is null) = (late is null)),
    -- PDF 는 제출 뒤에만 (생성 시점은 모의 제출 — 결정 2)
    constraint str_reports_pdf_after_report_ck check ((pdf_status is null) = (reported_at is null)),
    -- READY 면 파일 정보가 다 있고, 아니면 없다
    constraint str_reports_pdf_ready_ck check (
        (pdf_status is not distinct from 'READY') = (pdf_storage_key is not null)
        and (pdf_storage_key is null) = (pdf_sha256 is null)
        and (pdf_storage_key is null) = (pdf_size_bytes is null)
        and (pdf_storage_key is null) = (pdf_template_version is null)
        and (pdf_storage_key is null) = (pdf_generated_at is null)),
    -- 실패 코드는 FAILED 일 때만
    constraint str_reports_pdf_failure_ck check (
        (pdf_status is not distinct from 'FAILED') = (pdf_failure_code is not null))
);
-- 확정 보고서는 상태만 바뀌고 지우지 않는다 (5년 보존 근거).
revoke delete on ops.str_reports from app_backend;

-- cases 공개 조회·화면 마감 배지: 케이스로 찾기
create index str_reports_case_idx on ops.str_reports (case_id);
-- 아직 제출 안 한 보고서를 마감 순으로 (승인 완료 화면·마감 임박)
create index str_reports_due_open_idx on ops.str_reports (report_due_at) where reported_at is null;
