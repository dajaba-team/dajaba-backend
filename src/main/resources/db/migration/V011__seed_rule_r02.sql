-- V011: 룰 시드 — R02_FAN_IN 버전 1 (SCRUM-73·99, 룰 v2). 다른 룰은 구현할 때 같은 방식으로 새 파일에 넣는다.
-- 기준값·창 길이는 rules.params 가 정본(ML 규약 §2.3). v2 선정(9/1~9/10): 96시간·N=5. 바꾸면 새 버전 행으로.
-- 창은 B7(11/2): 대상 계좌에 입금이 있었던 분 E 마다 [E+1분-window_hours, E+1분).
insert into ops.rules(rule_id, version, name, typology_hint, params, is_active, created_by, updated_by)
values ('R02_FAN_IN', 1, '집중 입금', 'FAN_IN', '{"window_hours": 96, "min_distinct_senders": 5}'::jsonb, true, 1, 1);
