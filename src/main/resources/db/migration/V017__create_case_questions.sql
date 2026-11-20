-- V017: case_questions — 조사역 질문 답변 기록 (설계 문서 9.1, 계약서 5.3 A8·C9, SCRUM-302).
-- 담당 조사역·승인자가 케이스 근거에 물은 질문과 AI 답(규약 5.4)을 한 행씩 남긴다. 추가만 한다(이력 테이블 규칙, 01 원칙 3).
-- AI 호출 실패(502)는 행을 남기지 않는다 — 호출 흔적은 ai_call_logs 에 있다.
-- 묻는 사람은 created_by(세션, CreatedOnlyEntity auditing). 화면 번호는 QST-{id 6자리}(DisplayNumber.questionNo).
-- 머지 순서: … → V015 → V016 → V017 (05_flyway §3). V006(cases)·V003(users) 이 먼저 있어야 한다.

create table ops.case_questions (
    id                        bigint       generated always as identity primary key,
    case_id                   bigint       not null references ops.cases(id),
    -- 원문 그대로(앞뒤 공백·정규화 전 모양). 글자 수(NFC 뒤 UTF-16 1~300)는 앱이 본다 — varchar(300) 이면 NFD 로 쪼갠 한글 300자
    -- (코드 포인트 900개)를 앱은 받고 DB 는 거절한다. 그래도 끝없이 크지 않게 원문 코드 포인트 1,200개(NFD 한글 최대 4배)로 막는다
    question                  text         not null check (char_length(question) between 1 and 1200),
    status                    varchar(16)  not null
                              check (status in ('ANSWERED', 'NO_DATA', 'UNVERIFIED', 'OUT_OF_SCOPE')),
    out_of_scope_reason       varchar(16)
                              check (out_of_scope_reason in ('EDIT_REQUEST', 'OUTSIDE_CASE', 'JUDGMENT')),
    answer                    text,                              -- ANSWERED 일 때만. 답 속 계좌는 AI 표기 그대로(설계 문서 13절 8번)
    evidence_transaction_ids  bigint[]     not null default '{}'
                              check (array_position(evidence_transaction_ids, null) is null),
    ai_generation_id          varchar(64)  not null,             -- AI generation_id — ai.generations 와 값으로만 잇는다(FK 없음, 01 원칙 5)
    request_id                varchar(64),                       -- X-Request-Id — ai_call_logs.request_id 와 같은 값
    created_at                timestamptz  not null default now(),
    created_by                bigint       not null references ops.users(id),   -- 물은 사람(담당 조사역·승인자)

    -- 범위 밖 사유는 OUT_OF_SCOPE 일 때만, 답은 ANSWERED 일 때만 (규약 5.4 상태 표, 엔티티 CaseQuestion.record 와 같은 규칙)
    constraint case_questions_out_of_scope_ck check ((status = 'OUT_OF_SCOPE') = (out_of_scope_reason is not null)),
    constraint case_questions_answer_ck check ((status = 'ANSWERED') = (answer is not null)),
    -- 근거 거래는 ANSWERED 만 가질 수 있다 (ANSWERED 도 드물게 [])
    constraint case_questions_evidence_ck check (status = 'ANSWERED' or cardinality(evidence_transaction_ids) = 0)
);

-- C9 지난 질문: 케이스별 최신 먼저.
create index case_questions_case_created_idx on ops.case_questions (case_id, created_at desc, id desc);
-- A8 횟수 제한: 이 사람의 최근 60초 행 수.
create index case_questions_asker_created_idx on ops.case_questions (created_by, created_at);

revoke update, delete on ops.case_questions from app_backend;
