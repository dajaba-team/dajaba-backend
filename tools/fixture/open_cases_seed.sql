-- 5-2 케이스 생성 배치 수동 확인용 경보·점수 시드 (local 전용, 운영 DB에 넣지 않아요).
-- 점수 배치(SCRUM-100)·룰 스캔(SCRUM-99) 전이라 경보·점수를 SQL로 직접 넣어요. 점수는 목업 값이에요.
--
-- 넣는 것 (batch_date = 2022-09-02)
--   ALT-20220902-900001  SCORED 0.92 → HIGH   → 케이스 생겨야 함
--   ALT-20220902-900002  SCORED 0.81 → HIGH   → 케이스 생겨야 함
--   ALT-20220902-900003  SCORED 0.55 → MEDIUM → 안 생김 (경계값 0.4 / 0.7 기준)
--   ALT-20220902-900004  SCORED 0.12 → LOW    → 안 생김
--   ALT-20220902-900005  PENDING (점수 없음)  → 대상 아님
-- 기대 batch_runs: SUCCEEDED, rows_in 4 (SCORED 4건), rows_out 2.
--
-- 넣는 법 (compose db 기준):
--   docker exec -i aml-db psql -U postgres -d aml -v ON_ERROR_STOP=1 < tools/fixture/open_cases_seed.sql
-- 여러 번 넣어도 같은 경보는 다시 안 들어가요(alert_no 기준).

begin;

-- 경보가 가리킬 룰 스캔 실행 기록과 룰 한 줄 (FK용). triggered_by·created_by 1 = SYSTEM.
-- FAILED로 둬요: SUCCEEDED로 넣으면 나중에 진짜 룰 스캔(SCRUM-99)을 같은 날짜로 돌릴 때 BATCH_ALREADY_DONE에 막혀요.
insert into ops.batch_runs (batch_type, batch_date, status, finished_at, rows_in, rows_out, error_message, triggered_by)
select 'RULE_SCAN', date '2022-09-02', 'FAILED', now(), 0, 0, 'open_cases_seed', 1
where not exists (select 1 from ops.batch_runs where error_message = 'open_cases_seed');

insert into ops.rules (rule_id, version, name, typology_hint, params, is_active, created_by, updated_by)
values ('R90_SEED_ONLY', 1, '수동 확인용 시드 룰', 'FAN_IN', '{"seed": true}', false, 1, 1)
on conflict (rule_id, version) do nothing;

insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, entity_id,
                        window_start, window_end, reference_ts, rule_facts, batch_date, score_status)
select v.alert_no,
       (select id from ops.batch_runs where error_message = 'open_cases_seed' order by id limit 1),
       (select id from ops.rules where rule_id = 'R90_SEED_ONLY' and version = 1),
       v.account_key, v.entity_id,
       timestamp '2022-09-02 00:00:00', timestamp '2022-09-02 23:00:00', timestamp '2022-09-02 23:00:00',
       '{"seed": true}', date '2022-09-02', v.score_status
from (values
        ('ALT-20220902-900001', '010_8000EBD30', '80062E240', 'SCORED'),
        ('ALT-20220902-900002', '011_8001AAA01', '80062E241', 'SCORED'),
        ('ALT-20220902-900003', '012_8002BBB02', '80062E242', 'SCORED'),
        ('ALT-20220902-900004', '013_8003CCC03', '80062E243', 'SCORED'),
        ('ALT-20220902-900005', '014_8004DDD04', '80062E244', 'PENDING')
     ) as v(alert_no, account_key, entity_id, score_status)
where not exists (select 1 from ops.alerts a where a.alert_no = v.alert_no);

insert into ops.alert_scores (alert_id, score, threshold, model_version, feature_version,
                              prediction_id, produced_at, payload)
select a.id, v.score, 0.7, 'mock-model-0', 'mock-feature-0', 'seed-' || a.alert_no, now(),
       jsonb_build_object('alert_id', a.id, 'score', v.score, 'threshold', 0.7, 'data_quality_status', 'OK')
from (values
        ('ALT-20220902-900001', 0.92),
        ('ALT-20220902-900002', 0.81),
        ('ALT-20220902-900003', 0.55),
        ('ALT-20220902-900004', 0.12)
     ) as v(alert_no, score)
join ops.alerts a on a.alert_no = v.alert_no
on conflict (alert_id) do nothing;

commit;

-- 확인
select a.alert_no, a.score_status, s.score, a.case_opened
from ops.alerts a left join ops.alert_scores s on s.alert_id = a.id
where a.alert_no like 'ALT-20220902-9%' order by a.alert_no;
