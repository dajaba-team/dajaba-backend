-- 테스트 전용 (SCRUM-68). test 프로파일에서만 spring.flyway.locations 에 붙는다 — 운영 DB 에는 생기지 않는다.
-- AssignedIdEntity(미리 받은 id + 감사 4칸)를 실제 PostgreSQL 에서 확인하는 테이블. 모양은 str_draft_requests 와 같음(version 칸 없음).
-- 번호 9000 은 main 마이그레이션이 늘어나도 항상 맨 뒤에 붙게 하려는 것.

create table ops.test_assigned_items (
    item_id     bigint       primary key,           -- 시퀀스에서 미리 받은 번호를 넣는 자리 (자동 발급 아님)
    name        varchar(100) not null,
    created_at  timestamptz  not null,
    created_by  bigint       not null,
    updated_at  timestamptz  not null,
    updated_by  bigint       not null
);
