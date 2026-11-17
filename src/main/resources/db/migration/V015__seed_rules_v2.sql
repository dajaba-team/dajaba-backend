-- V015: 룰 시드 — R01·R03·R04·R05·R06 버전 1 (SCRUM-260, 룰 v2). V011(R02)과 같은 방식으로 나머지 다섯 개를 한 번에 넣는다.
-- 기준값은 rules.md v2(11/10) 그대로 = 학습용 PARAMS(ML 규약 §2.3). 바꿀 때는 이 파일을 고치지 않고 새 버전 행을 넣는다.
-- 구현 전 룰은 RuleScanner 가 건너뛴다 (임시, 마지막 룰 PR SCRUM-193 에서 되돌림).
insert into ops.rules(rule_id, version, name, typology_hint, params, is_active, created_by, updated_by)
values
    ('R01_FAN_OUT', 1, '분산 송금', 'FAN_OUT',
     '{"window_hours": 96, "min_distinct_receivers": 10}'::jsonb, true, 1, 1),
    ('R03_FX_RELAY', 1, '환전 중계', 'FX_RELAY',
     '{"window_hours": 96, "amount_tolerance": 0.1, "require_currency_change": true,
       "usd_rates": {"US Dollar": 1, "Euro": 0.8534, "UK Pound": 0.7742, "Yen": 105.4, "Yuan": 6.6976,
                     "Rupee": 73.444, "Mexican Peso": 21.1431, "Bitcoin": 0.00008421, "Ruble": 77.804,
                     "Canadian Dollar": 1.3193, "Swiss Franc": 0.915, "Australian Dollar": 1.4128,
                     "Brazil Real": 5.6465, "Saudi Riyal": 3.7511, "Shekel": 3.377}}'::jsonb, true, 1, 1),
    ('R04_CYCLE', 1, '순환 거래', 'CYCLE',
     '{"window_hours": 96, "max_hops": 4}'::jsonb, true, 1, 1),
    ('R05_SCATTER_GATHER', 1, '흩었다 모으기', 'SCATTER_GATHER',
     '{"window_hours": 96, "min_intermediates": 3}'::jsonb, true, 1, 1),
    ('R06_GATHER_SCATTER', 1, '모았다 흩기', 'GATHER_SCATTER',
     '{"window_hours": 96, "min_distinct_senders": 5, "max_distinct_senders": 100,
       "min_distinct_receivers": 5, "max_distinct_receivers": 100}'::jsonb, true, 1, 1);
