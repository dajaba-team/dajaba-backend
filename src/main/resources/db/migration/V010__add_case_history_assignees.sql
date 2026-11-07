-- V010: case_history 에 넘긴·받은 담당자 (W4 지윤, 미결 32 — 예전 담당 자기 승인 막기, FR-CASE-11·FR-APR-04·05).
-- 승인·반려 때 "이 사람이 이 케이스를 맡은 적이 있나"를 이력으로 판정한다 (cases WasCaseAssignee).
-- ASSIGN 은 받은 사람(to)만, REASSIGN 은 넘긴 사람(from)·받은 사람(to) 둘 다. 그 밖의 행동은 둘 다 비운다.
-- V006 뒤. V007 은 비워 둔다 (05_flyway §3).

alter table ops.case_history
    add column from_assignee_id bigint references ops.users(id),   -- REASSIGN 에서 넘긴 사람
    add column to_assignee_id   bigint references ops.users(id);   -- ASSIGN·REASSIGN 에서 받은 사람

-- 배정·재배정이 아닌 행은 담당자 칸을 쓰지 않는다. 지금까지의 행은 두 칸이 모두 비어 있으니 바로 검사한다.
alter table ops.case_history
    add constraint case_history_assignee_only_ck check (
        action in ('ASSIGN', 'REASSIGN') or (from_assignee_id is null and to_assignee_id is null));

-- 배정은 받은 사람만(넘긴 사람 없음), 재배정은 넘긴·받은 사람 모두 있어야 하고 서로 달라야 한다.
-- V010 전에 쌓인 ASSIGN·REASSIGN 행은 두 칸이 비어 있어서 NOT VALID 로 건다 — 새로 넣는 행부터 검사한다.
-- case 로 나눠 NULL 이 섞여도 true/false 로만 나오게 한다 (V006 case_history_reason_code_ck 와 같은 이유).
alter table ops.case_history
    add constraint case_history_assignee_required_ck check (case
        when action = 'ASSIGN'   then from_assignee_id is null and to_assignee_id is not null
        when action = 'REASSIGN' then from_assignee_id is not null and to_assignee_id is not null
                                      and from_assignee_id <> to_assignee_id
        else true end) not valid;

-- 자기 승인 판정: 케이스별로 받은 사람이 있는지만 본다.
create index case_history_to_assignee_idx on ops.case_history (case_id, to_assignee_id)
    where to_assignee_id is not null;
