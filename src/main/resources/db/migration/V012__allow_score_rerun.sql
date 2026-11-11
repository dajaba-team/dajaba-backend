-- V012: 점수 배치(SCORE_REQUEST)는 성공한 날도 다시 돌린다 (안건 14·21, 미결 4, SCRUM-100).
-- 다시 돌리면 남은 대상(PENDING, 그리고 FAILED 중 score_failure_code 가 NULL·COMPUTE_ERROR)만 AI 에 보낸다.
-- 적재·룰 스캔·케이스 생성은 그대로 성공 1회. 같은 날짜 점수 배치 동시 실행은 앱이 막는다(시작 락 + RUNNING 확인).
-- RUNNING 조건·PARTIAL 상태·metrics 칸(미결 4의 나머지)은 이 파일에서 다루지 않는다.

drop index ops.batch_runs_succeeded_uq;

create unique index batch_runs_succeeded_uq
    on ops.batch_runs (batch_type, batch_date)
    where status = 'SUCCEEDED' and batch_type <> 'SCORE_REQUEST';
