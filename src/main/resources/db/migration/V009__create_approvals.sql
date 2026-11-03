-- V009: approvals — 승인·반려 기록. 추가만 한다(CreatedOnlyEntity, created_by = 승인자).
-- V008(str_drafts·str_revisions·verification_results)이 먼저 있어야 한다.
-- 머지 순서: V004_1 → V004_2 → V004_3 → V005 → V005_1 → V005_2 → V006 → V008 → V009 (05_flyway §3).
-- case_id 는 두지 않는다 — 초안은 케이스를 바꾸지 않으므로 str_drafts.case_id 로 찾는다.
-- 복합 FK 대상 verification_results (draft_id, id) 유니크는 V008 에 있다 (#28 2차 리뷰).

create table ops.approvals (
                               id                      bigint generated always as identity primary key,
                               draft_id                bigint not null references ops.str_drafts(id),
                               revision_id             bigint not null,                    -- 어떤 본문을 (승인·반려 당시 현재 수정본)
                               verification_result_id  bigint,                             -- 승인 근거가 된 검증 (verification_results.id). 승인이면 필수
                               decision                varchar(16) not null
                                   check (decision in ('APPROVED', 'REJECTED')),
                               reason_code             varchar(32)
                                   check (reason_code in ('NEED_REVISION', 'NOT_REPORTABLE')),  -- cases.rejection_reason_code 와 같은 값
                               narrative_sha256        char(64) not null check (narrative_sha256 ~ '^[0-9a-f]{64}$'),  -- 승인·반려 당시 본문 해시
    comment                 text check (char_length(comment) <= 2000),                     -- 반려 사유
    created_at              timestamptz not null default now(),
    created_by              bigint not null references ops.users(id),                      -- 승인자
    -- 승인: 검증 필수, 반려 코드 없음 / 반려: 반려 코드와 사유(공백 아님) 필수 (안건 24)
    constraint approvals_decision_fields_ck check (
        (decision = 'APPROVED' and verification_result_id is not null and reason_code is null)
        or (decision = 'REJECTED' and reason_code is not null
            and comment is not null and length(btrim(comment)) > 0)
    ),
    constraint approvals_draft_revision_fk foreign key (draft_id, revision_id)
        references ops.str_revisions (draft_id, id),                -- 그 초안의 수정본
    constraint approvals_draft_verification_fk foreign key (draft_id, verification_result_id)
        references ops.verification_results (draft_id, id)          -- 그 초안의 검증
);
revoke update, delete on ops.approvals from app_backend;

-- 초안당 승인은 한 번 (승인되면 초안이 LOCKED 라 다시 승인할 일이 없다). 반려는 여러 번 가능.
create unique index approvals_one_approved_uq on ops.approvals (draft_id) where decision = 'APPROVED';
-- 초안별 최근 기록 (보완 요청 뒤 같은 본문 검사는 마지막 반려의 narrative_sha256 과 비교)
create index approvals_draft_created_idx on ops.approvals (draft_id, created_at desc, id desc);