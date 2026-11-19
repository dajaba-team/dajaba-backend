-- V018: 점수 실패 코드에 RULE_NOT_SUPPORTED 추가 (SCRUM-326, ML 규약 v0.4 11/19, AI #81).
-- 모델이 점수를 내지 않는 룰(rc1 은 R03·R05·R06) — 다시 보내도 같은 결과라 재처리하지 않는다(재처리는 코드 NULL·COMPUTE_ERROR 그대로, V012).
-- V005 의 이름 없는 CHECK 는 기본 이름 alerts_score_failure_code_check. 같은 이름으로 다시 넣는다.
alter table ops.alerts drop constraint alerts_score_failure_code_check;
alter table ops.alerts add constraint alerts_score_failure_code_check
    check (score_failure_code in ('TRANSACTION_NOT_FOUND', 'HISTORY_NOT_SUPPORTED',
                                  'REQUIRED_INPUT_MISSING', 'COMPUTE_ERROR', 'RULE_NOT_SUPPORTED'));
