-- V008: str — 초안 요청 기록, 초안, 수정본, 검증 결과 + cases.current_draft_id FK.
-- V006(ops.cases)이 먼저 있어야 한다. 머지 순서: V004_1 → V004_2 → V004_3 → V005 → V005_1 → V006 → V008 (05_flyway §3).

-- draft_id 는 AI 호출 전에 이 시퀀스로 먼저 받는다. str_drafts.id 와 같은 값이다.
create sequence ops.str_draft_id_seq as bigint start with 1 increment by 1 no cycle;

-- 초안 요청 기록. AI를 부르기 전에 짧은 트랜잭션으로 커밋한다 (미결 21).
create table ops.str_draft_requests (
                                        draft_id          bigint primary key,                        -- nextval('ops.str_draft_id_seq')
                                        case_id           bigint not null references ops.cases(id),
                                        status            varchar(16) not null default 'REQUESTED'
                                            check (status in ('REQUESTED', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
                                        request_snapshot  jsonb not null,                            -- AI에 보낸 요청. 재전송은 이 값을 그대로 보낸다
                                        requested_by      bigint not null references ops.users(id),
                                        requested_at      timestamptz not null default now(),
                                        finished_at       timestamptz,                               -- REQUESTED 가 아닌 상태로 바뀐 시각
                                        constraint str_draft_requests_finished_ck check ((status = 'REQUESTED') = (finished_at is null))
);
-- 케이스마다 진행 중인 요청은 하나. 다시 누르면 그 draft_id 를 다시 쓴다.
create unique index str_draft_requests_one_requested_uq
    on ops.str_draft_requests (case_id) where status = 'REQUESTED';

-- 초안. AI 응답 원문(payload)과 뽑아 둔 칸. 사람이 고치는 테이블이라 감사 컬럼 4개 + version.
create table ops.str_drafts (
                                id                      bigint primary key references ops.str_draft_requests(draft_id),  -- = draft_id
                                case_id                 bigint not null references ops.cases(id),
                                generation_id           varchar(64) not null unique,          -- AI가 발급 (안건 23)
                                generator_version       varchar(64) not null,                 -- AI 생성기 버전 (AiDtos.generatorVersion, 서식 안건 2)
                                produced_at             timestamptz not null,                 -- AI가 UTC Z 로 준 생성 시각 (미결 27)
                                payload                 jsonb not null,                       -- AI 응답 전체
                                facts                   jsonb generated always as (payload -> 'facts') stored,  -- payload 에서 자동. 직접 넣거나 고칠 수 없다
                                status                  varchar(16) not null default 'ACTIVE'
                                    check (status in ('ACTIVE', 'SUPERSEDED', 'LOCKED')),
                                current_revision_id     bigint,                               -- 아래에서 str_revisions FK. 첫 저장 직후 1번으로 채운다
                                suspicion_types         varchar(16)[] not null default '{}',  -- 서식 VI 유형 번호 (서식 안건 3·5-3)
                                suspicion_level_branch  smallint check (suspicion_level_branch between 1 and 5),  -- 지점용, 조사역
                                suspicion_level_hq      smallint check (suspicion_level_hq between 1 and 5),      -- 본점용, 승인자
                                version                 bigint not null default 0 check (version >= 0),
                                created_at              timestamptz not null default now(),
                                created_by              bigint not null references ops.users(id),
                                updated_at              timestamptz not null default now(),
                                updated_by              bigint not null references ops.users(id)
);
-- 케이스마다 사용 중인 초안은 하나. 재생성하면 예전 초안은 SUPERSEDED.
create unique index str_drafts_one_active_uq on ops.str_drafts (case_id) where status = 'ACTIVE';
create index str_drafts_case_idx on ops.str_drafts (case_id, created_at desc, id desc);

-- 수정본. 추가만 한다. 1번 = AI 원문(작성자 SYSTEM, 고치기 전 없음), 첫 수정은 2번 (미결 25).
create table ops.str_revisions (
                                   id                bigint generated always as identity primary key,
                                   draft_id          bigint not null references ops.str_drafts(id),
                                   revision_no       int not null check (revision_no >= 1),
                                   narrative_before  jsonb not null,                            -- 1번은 [] (앞이 없음)
                                   narrative_after   jsonb not null,
                                   changed_sections  varchar(32)[] not null default '{}',       -- 1번은 비어 있음
                                   created_at        timestamptz not null default now(),
                                   created_by        bigint not null references ops.users(id),
                                   constraint str_revisions_draft_no_uq unique (draft_id, revision_no)
);
revoke update, delete on ops.str_revisions from app_backend;

alter table ops.str_drafts
    add constraint str_drafts_current_revision_fk
        foreign key (current_revision_id) references ops.str_revisions(id);

-- 검증 결과. 추가만 한다. 승인 게이트는 generation_id + 본문 해시 + status 로 찾는다 (안건 23).
-- 같은 초안·같은 해시에 여러 줄이면 가장 최근 것만 인정한다 (11/1 확정, W4 SubmitForApproval).
create table ops.verification_results (
                                          id                bigint generated always as identity primary key,
                                          verification_id   varchar(64) not null unique,               -- AI가 발급. 승인 요청이 이 값으로 고른다
                                          draft_id          bigint not null references ops.str_drafts(id),
                                          generation_id     varchar(64) not null,
                                          revision_no       int not null check (revision_no >= 1),     -- 기록용. 게이트는 비교하지 않는다
                                          narrative_sha256  char(64) not null check (narrative_sha256 ~ '^[0-9a-f]{64}$'),
    status            varchar(32) not null
                      check (status in ('PASSED', 'CODE_FAILED', 'LLM_FAILED', 'VERIFY_UNAVAILABLE')),
    passed            boolean generated always as (status = 'PASSED') stored,  -- status 에서 자동. 게이트는 status 로 판단
    verifier_version  varchar(64) not null,                      -- AI 검증기 버전 (서식 안건 2)
    produced_at       timestamptz not null,                      -- AI가 UTC Z 로 준 검증 시각
    payload           jsonb not null,                            -- AI 응답 전체 (code_checks, llm_checks, narrative_refs)
    created_at        timestamptz not null default now(),
    created_by        bigint not null references ops.users(id)
);
revoke update, delete on ops.verification_results from app_backend;
create index verification_results_latest_idx
    on ops.verification_results (draft_id, narrative_sha256, created_at desc, id desc);

-- 순환 참조: V006 이 만든 cases.current_draft_id 에 FK 를 건다 (05_flyway §1).
alter table ops.cases
    add constraint cases_current_draft_fk
        foreign key (current_draft_id) references ops.str_drafts(id);