-- 배치 결과 상세 (SCRUM-349) — 룰 스캔·점수가 202 비동기로 바뀌어 응답 대신 여기 남긴다. 상태 조회(B5)·목록이 그대로 내려준다.
-- 룰 스캔 {"alerts_by_rule":[...]}, 점수 {"requested","scored","failed","retryable","chunks","model_versions"}. 그 밖·예외 실패는 null.
-- 계좌·금액·본문은 넣지 않는다(건수·룰·모델 이름만).
alter table ops.batch_runs add column summary jsonb;
