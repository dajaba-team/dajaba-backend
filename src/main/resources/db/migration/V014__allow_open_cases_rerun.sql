-- V014: 케이스 생성 배치(OPEN_CASES)도 성공한 날 다시 돌린다 (회의 14 보충 실행, 안건 21, SCRUM-238).
-- V012 와 같은 방식 — batch_runs_succeeded_uq 를 다시 만들어 SCORE_REQUEST·OPEN_CASES 를 뺀다.
-- 다시 돌려도 FindAlertsForCaseOpening 이 "SCORED·아직 케이스 없는(case_opened = false)" 경보만 주므로 새로 HIGH 가 된 경보만 케이스가 된다.
-- 기존 케이스는 다시 만들지 않는다(cases.alert_id UNIQUE, MarkCaseOpened). 같은 날짜 동시 실행은 앱이 막는다(시작 advisory lock + RUNNING 확인).
-- 적재·룰 스캔은 그대로 성공 1회.
drop index ops.batch_runs_succeeded_uq;
create unique index batch_runs_succeeded_uq
    on ops.batch_runs (batch_type, batch_date)
    where status = 'SUCCEEDED' and batch_type not in ('SCORE_REQUEST', 'OPEN_CASES');
