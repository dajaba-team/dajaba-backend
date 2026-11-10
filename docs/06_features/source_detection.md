# 06. source · detection — 적재 · 룰 · 경보 · 점수

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 담당 준혁 · 정리 진희

담당자 문서는 아직 초안이라 `확정` / **〔초안〕** / **〔확인 필요〕**를 나눠 적었어요(뜻은 [`../README.md`](../README.md) "읽는 법"). 이 파일은 **담당자가 고쳐요.** 노션 초안을 바꾸면 같은 주에 이 파일도 PR로 갱신해 주세요.

## source — 적재 (준혁)

**하는 일** — IBM HI-Small CSV 3개(`HI-Small_Trans.csv`, `HI-Small_accounts.csv`, `HI-Small_Patterns.txt`)를 원천 4개 테이블에 **한 번** 적재해요. 이후 원천은 읽기 전용이고, AI 서버(`app_ai`)도 이 4개를 직접 읽어요. [레퍼런스 §11.2, SD 명세 §1]

**확정**
- 원천 4개는 적재 후 UPDATE·DELETE 없음. 적재는 빈 DB에서만(이미 있으면 409). [레퍼런스 §11.1]
- 적재 실패는 트랜잭션 롤백으로 원천에 남지 않아 따로 비우지 않아요. 다시 적재하려면 `docker compose down -v`로 DB를 새로 만들어요. `app_backend`에는 원천 UPDATE·DELETE·TRUNCATE 권한이 없어요.
- 계좌 파일은 이미 있는 계좌·은행·법인과 값이 같으면 건너뛰고, 소유자·은행 이름·법인 이름이 다르면 실패해요. 은행 이름은 필수이고, 하나로 정해지지 않는 파일(앞 0만 다른 ID에 다른 이름)도 실패예요. MVP 적재는 빈 DB에서 1회라 실제로는 첫 적재 검증이에요. [SCRUM-54 리뷰]
- `is_laundering`·`pattern_type`·`pattern_id`는 적재하지만 **백엔드 엔티티에 매핑하지 않고 응답에도 없어요.** 적재 SQL 안에서 검증용으로만 세요. [레퍼런스 §10-10, SD 명세 §3]
- 적재는 JPA가 아니라 `CopyManager`(COPY)로. `transactions_staging`(unlogged)을 거쳐 `INSERT … SELECT`로 변환. [레퍼런스 §11.2]
- 서비스 DB에는 **HI-Small만** 들어가요(SAML-D·LI는 학습 전용). 준영이 준 검증값(`expected.json`)으로 적재 결과를 같이 확인해요. [안건 12·13]
- 금액은 `numeric(20,6)`. 원본 금액을 반올림하지 않아요(Bitcoin 소수 6자리). [안건 19, 11/2 준혁·준영 합의, V005_2] 〔10/27의 `numeric(18,2)` 유지는 실제 파일에서 293,425개 필드가 바뀌어 변경〕
- **은행 ID는 앞 0을 빼고 정규화**해 `account_key`를 만들어요(`023842` → `23842`, 예 `10_8000EBD30`). 거래·계좌 파일에 같은 규칙, 원본 표기는 `from_bank_id_raw`·`to_bank_id_raw`(V004_1), 계좌번호는 원문 그대로. 11/2 전체 파일에서 키 충돌·거래 계좌 미연결 0을 확인했고 ML 규약도 같은 규칙이에요. [미결 6, 서식 안건 5-1, SCRUM-54] 〔SD 명세 §11.2·공통 규약 §0의 "앞 0 보존"은 그 전 안 — 공통 규약은 반영 요청(SCRUM-71)〕
- **`transaction_id` — DB가 자동 발급.** 원본 추적은 `source_files` 테이블로: `source_files(id, file_name, file_hash CHAR(64) UNIQUE)`, `transactions.source_file_id`(FK), `source_row_number`, `UNIQUE(source_file_id, source_row_number)`. CSV 행 번호·적재 순서로 ID를 추정하지 않아요. [안건 1, ML 규약 §1.1] 〔레퍼런스 §2·SD 명세 §11.2의 "CSV 행 번호를 그대로 PK"는 그 전 안〕
- `customer_attributes`는 백엔드가 만드는 **가상 KYC**(`entity_id` 해시를 시드로 — 다시 만들어도 같은 값). AI 피처로는 안 써요. [레퍼런스 §3.1, ML 규약 §1]

**적재 순서** 〔초안 — SD 명세 §3〕
1. 컨트롤러가 세션에서 요청자 ID를 꺼내 넘김(비동기 스레드엔 세션 없음)
2. `batch_runs` RUNNING 커밋 → **202** 응답
3. 스테이징 비우기 → 거래 CSV `COPY … FROM STDIN`
4. 패턴 파일 파싱(BEGIN~END 블록 = 한 사건)
5. `INSERT INTO ops.transactions SELECT …` — 시각 파싱, `account_key` 생성, `is_self_transfer` 계산, 패턴 LEFT JOIN
6. 계좌 파일을 읽어 법인을 뽑고(중복 제거), **DB에는 `entities` → `accounts` → `customer_attributes` 순서로 넣어요.** `accounts.entity_id`가 `entities`를 가리키는 필수 FK라 계좌를 먼저 넣으면 FK에 걸려요(V002). [10/29 설계 점검]
7. 검증 쿼리 — 하나라도 다르면 실패
8. 스테이징 비우기, `batch_runs` SUCCEEDED

트랜잭션 세 개: ① RUNNING 기록(즉시 커밋) ② 적재 전체 ③ 결과 기록. ①·③은 `REQUIRES_NEW`. ②가 실패하면 원천 4개는 전부 롤백되고 FAILED 기록만 남아요.

**검증값** 〔11/1 실제 파일 전체 적재로 확정 — `DatasetExpectation.HI_SMALL`, 규약 반영은 SCRUM-71〕
| 항목 | 값 |
|---|---|
| 거래 수 | 5,078,345 |
| 세탁 거래 수 | 5,177 |
| 자기계좌 거래 수 | 591,212 |
| 기간 | 2022-09-01 00:00 ~ 2022-09-18 16:18 (실질 9/1~9/10) |
| 법인 수 | 166,207 |
| 계좌 수 | 518,581 (규약 518,573 아님 — 11/1 실측) |
| 거래 등장 고유 계좌 수 | 515,088 (송신·수신 합집합) |
| 패턴 사건 / 패턴 거래 | 370 / 3,209 |
| 계좌 파일에 없는 거래 계좌 | 0 |
| 패턴 미매칭 / 다중매칭 행 | 0 / 0 |

**〔확인 필요〕**
- **통화 표기** — DB엔 IBM 원문 이름(`US Dollar`)이 들어가요. 규약 v0.2의 STR facts는 ISO 코드(`USD`)라 AI 서버가 읽을 때 바꿔요. 백엔드 화면·`rule_facts`의 통화를 원문으로 둘지 코드로 바꿀지, 매핑표를 누가 관리할지 정해야 해요. [CASES 명세 §4는 "SOURCE 매핑표를 따른다"]
- 서식 반영 — `entity_type` 6종 → 서식 표기(5-6), `accounts.bank_country`(5-10), `customer_attributes`를 508만 건 전체 계좌에 만들지(5-7). 서식 회의 전.

**API** — `POST /api/v1/admin/batches/load`(ADMIN, 202), 상태는 `GET /api/v1/admin/batches/{batch_run_id}`. 실패 응답: 이미 적재됨 409 `BATCH_ALREADY_DONE`, 같은 파일 재적재 409 `SOURCE_FILE_ALREADY_LOADED`(파일명이 아니라 SHA-256 기준), 실행 중 409 `BATCH_RUNNING`, CSV 없음 409 `DATASET_FILES_MISSING`〔초안〕.

---

## detection — 룰·경보·점수 (준혁)

**하는 일** — 날짜별로 룰을 돌려 `alerts`를 만들고, AI에 점수를 받아 `alert_scores`에 저장해요. 사람이 고치는 건 `rules`의 기준값뿐이에요. [가이드 §1]

**테이블** [레퍼런스 §3.2]
- `rules` — `rule_id`(`R02_FAN_IN`), `version`(기준값 바뀔 때마다 +1, `(rule_id, version)` unique), `name`, `typology_hint`, `params jsonb`, `is_active`. 감사 4. 끈 룰도 행은 살려 둬요(과거 경보가 가리킴).
- `alerts` — `alert_no`(unique), `batch_run_id`, `rule_ref_id`(어느 버전의 룰), `account_key`, `entity_id`(null 가능), `window_start`, `window_end`, `reference_ts`, `rule_facts jsonb`, `batch_date`, `score_status`(PENDING/SCORED/FAILED), `score_failure_code`(null 가능 — FAILED일 때 AI `failure_code`, 10/29), `case_opened`. unique `(rule_ref_id, account_key, window_start)`. 만든 뒤 바뀌는 건 `score_status`·`score_failure_code`·`case_opened`뿐.
- `alert_transactions` — 경보 ↔ 거래 N:M, 복합 PK.
- `alert_scores` — `score`(not null — 점수 있는 경보만 행을 만들고, FAILED는 행 없음), `threshold`(AI 추천값), `model_version`, `feature_version`, `prediction_id`, `produced_at`, `payload jsonb`(응답 원문 — `data_quality_status`도 여기서 읽어요, 10/29 준혁). **MVP는 `alert_id` UNIQUE 유지**, 다른 모델로 재채점은 고도화. [안건 2·4·5·21, ML 규약 §6.3] 〔레퍼런스·SD 명세의 "경보 하나에 점수 여러 행"은 그 전 안〕 (V005_1, SCRUM-96)

**룰 6개 — v2 (11/9 확정)** [노션 「탐지 룰 v2 기준값 선정 근거」 §3.4, 안건 20]
| 번호 | 룰 ID | 조건 | v2 `params` | 지금 |
|---|---|---|---|---|
| R01 | `R01_FAN_OUT` | 서로 다른 수신 계좌 N개 이상으로 송금 | `window_hours` 96, `min_distinct_receivers` 10 | |
| R02 | `R02_FAN_IN` | 서로 다른 송신 계좌 N개 이상에서 입금 | `window_hours` 96, `min_distinct_senders` 5 | **구현됨 (SCRUM-99)** |
| R03 | `R03_FX_RELAY` — 환전 중계 | 받은 거래보다 늦은 분, 창 안에 **다른 통화로** 보낸 거래가 있고 USD 환산 금액 비율(보낸 ÷ 받은)이 0.9~1.1(양 끝 포함). 받은 거래마다 창 안 출금 전부를 봄(건수 상한 없음) | `window_hours` 96, `amount_tolerance` 0.1, `require_currency_change` true, `usd_rates`(15개 통화 고정표) | 옛 `R03_PASS_THROUGH`를 대체 — 번호만 재사용, ID는 다름 |
| R04 | `R04_CYCLE` | 대표 계좌에서 나가 같은 계좌로 돌아오는 고리 | `window_hours` 96, `max_hops` 4 | `rule_facts.paths` 필수 |
| R05 | `R05_SCATTER_GATHER` | 흩었다 모으기 — 한 계좌에서 서로 다른 중간 계좌 N개 이상을 거쳐 한 계좌로 모임 | `window_hours` 96, `min_intermediates` 3 | `rule_facts.paths` 필수. 옛 이름 `R05_STACK` |
| R06 | `R06_GATHER_SCATTER` | 모았다 흩기 — 입금 상대·출금 상대가 **각각** 하한~상한 안 | `window_hours` 96, `min_distinct_senders` 5, `max_distinct_senders` 100, `min_distinct_receivers` 5, `max_distinct_receivers` 100 | 옛 `R06_BIPARTITE`를 대체 |

- R07·R08은 v2에서 뺐어요. 번호와 ID는 다른 룰에 다시 쓰지 않아요.
- `usd_rates`는 실시간 환율이 아니라 HI-Small에서 뽑은 고정값이에요. 백엔드와 AI 전처리가 같은 표·같은 환산 방향(금액 ÷ `usd_rates[통화]`)을 써요.

**룰 공통** [안건 20, B7 11/2, 룰 v2]
1. **룰 ID**는 `번호_유형`이고 코드·DB·학습 데이터에서 같은 문자열이에요. 시간은 ID에 넣지 않고 `params.window_hours`에 둬요.
2. **창**은 B7 슬라이딩이에요. H = `window_hours`(v2 = 96)일 때 지금 창은 `[E+1분-H, E+1분)`, 1분 전 창은 `[E-H, E)`예요. 1분 전엔 거짓이고 지금 참일 때만 경보를 만들어요. `reference_ts` = `window_end` = E+1분이에요.
3. **평가 시점**은 관련 거래가 있는 분뿐이에요.
   - R01·R02·R06은 그 계좌에 거래가 있는 분에 평가해요.
   - R03·R04·R05는 중계·경로의 마지막 거래가 있는 분에 평가해요.
   - 오래된 거래가 창에서 빠지기만 해서 참이 된 경우엔 경보를 만들지 않아요.
4. **순서**: 중계·경로의 다음 거래는 앞 거래보다 **늦은 분**이어야 해요(같은 분은 안 돼요). 모든 거래가 창 안에 있어야 해요.
5. **자기계좌 거래는 빼요.**
6. **금액**: 상대 수를 세는 룰은 금액을 보지 않아요. R03만 USD로 환산해 비교해요. `rule_facts`의 금액은 통화 원문 그대로 통화별로 적어요.

룰 명세는 준혁이 관리해요. 준영의 학습용 경보와 `expected.json`으로 건수·거래 ID를 맞춰 봐요.

**`rule_facts.paths` (R04·R05)** [ML 규약 §2.4] — 경로별 실제 거래 ID의 시간순 배열(`[[…],[…]]`)이에요.
- 합집합 = `transaction_ids`예요.
- R04는 모든 고리가 대표 계좌에서 시작해 같은 계좌로 돌아와요.
- 창 안의 **모든 유효 경로**를 담아요. 9/1~9/10 기준 경보당 최대 경로는 R04 16개, R05 106개예요.
- 경로를 임의로 재배열·삭제하지 않아요.

**룰 엔진 = 두 단계** [레퍼런스 §6.1 ③]
1. **집계는 SQL**(`RuleScanJdbcRepository`): R02는 그날 입금이 있었던 (계좌, 분 E)마다 지금 창 `[E+1분-H, E+1분)`과 1분 전 창 `[E-H, E)`의 서로 다른 송금 계좌 수(H = `params.window_hours`). 하루 범위에서도 N이 안 되는 계좌는 먼저 걸러요
2. **판정은 Java**: `FanInRule.fires(FanInMinute)` — 지금 ≥ N이고 1분 전 < N(거짓→참)일 때만 경보 1건. 기준값(`window_hours`·`min_distinct_senders`)이 없거나 정수가 아니거나 1 미만이면 409 `INVALID_RULE_PARAMS` — 기본값으로 조용히 돌지 않아요 [SCRUM-99]
- 룰별 조건·창·기준값·`rule_facts`는 룰 명세 `rules.md`(SCRUM-73)가 기준이에요.

**룰 스캔 — 구현됨 (SCRUM-99, R02)** `POST /api/v1/admin/batches/rule-scan?date=` → 200 `{batch_run_id, batch_type, batch_date, status, rows_in(그날 거래 수), rows_out(경보 수), alerts_by_rule[{rule_id, rule_version, alert_count}], started_at, finished_at}`
- 시작 전 거절(행 안 남김): 거래 없음 409 `DATASET_NOT_LOADED` · 거래 첫날~마지막 날 밖 409 `DATE_OUT_OF_RANGE` · 실행 중 409 `BATCH_RUNNING` · 이미 성공 409 `BATCH_ALREADY_DONE` · 켜진 룰 없음 409 `NO_ACTIVE_RULES`. 〔SD 명세 v0.3의 400 두 개는 409로 — 400은 요청 형식 오류에만〕
- 룰 전체가 **한 트랜잭션**(하나라도 실패하면 그 실행의 경보 전부 롤백 → 다시 돌려도 두 번 생기지 않음), `SUCCEEDED`도 그 안에서. 실패는 `FAILED`를 따로 커밋. 앱이 죽어 남은 `RUNNING`은 기동 때 `FAILED`로.
- 경보: `alert_no`는 시퀀스, `score_status = PENDING`, `case_opened = false`. R02 창은 B7(11/2) 슬라이딩 `window_hours`(v2 96시간) — `window_end = reference_ts = E+1분`(뜻은 `(E-H, E]`), `batch_date` = 스캔 날짜(E의 날짜). 자기 계좌 거래 제외. `entity_id`는 `accounts`.
- R02 `rule_facts` = `{distinct_senders, amounts_by_currency:[{currency, total_amount, transaction_count, transaction_ids}]}`(받은 쪽 금액, 통화 원문 그대로). `transaction_ids` = 창 안 입금 전부.
- 룰 행은 Flyway 시드(`V011` = `R02_FAN_IN` 버전 1, 96시간·N = 5). 다른 룰은 구현할 때 같은 방식으로 새 파일에 넣어요. 켜진 룰에 구현이 없으면 500(배치 FAILED).
- 룰 기준값은 기존 행을 고치지 않고 새 버전 행을 추가해요. DB도 `rules`는 `is_active`·`updated_*`만 바꿀 수 있어요(V005).
- 이미 성공한 날짜는 다시 스캔하지 않아요(`batch_runs`). 그래서 룰 버전을 올려도 같은 날짜에 같은 룰·계좌·창 경보가 버전별로 또 생기지 않아요. 새 버전은 다음 스캔부터 적용돼요.

**점수 요청** [레퍼런스 §11.1, 안건 2·4·14·21, ML 규약 §2·§3·§6]

**점수 배치 — 구현됨 (SCRUM-100)** `POST /api/v1/admin/batches/score?date=` → 200 `{batch_run_id, batch_type, batch_date, status, requested, scored, failed, chunks{total, failed}, model_versions, started_at, finished_at}` (동기)
- 시작 전 거절(행 안 남김): 그 날짜 룰 스캔 성공 없음 409 `RULE_SCAN_NOT_DONE` · 실행 중 409 `BATCH_RUNNING`. **성공한 날도 다시 돌려요**(V012) — 대상이 없으면 AI를 부르지 않고 `requested: 0`으로 SUCCEEDED.
- 대상을 경보 id 순으로 `aml.ai.max-batch`(500)개씩 잘라 묶음마다 `AiClient.scores()`(트랜잭션 밖) → 저장은 묶음마다 한 트랜잭션.
- 묶음 호출이 실패하면(재시도 1회 뒤, `AI_BAD_RESPONSE` 포함) 그 묶음만 `FAILED` + 코드 NULL로 커밋하고 다음 묶음으로 가요. 앞 묶음의 `SCORED`는 그대로.
- 항목 확인(`ScoreOutcome`): 점수 상태 3값 + `prediction_id`·버전(64자 이하) + 오프셋 있는 `produced_at`이면 `SCORED` + `alert_scores` 한 행. `FAILED` 항목은 코드가 4값이면 그대로, 아니면 NULL. 그 밖은 `FAILED` + NULL.
- `batch_runs`: 다시 보낼 실패(코드 NULL·`COMPUTE_ERROR`)가 남으면 **FAILED**(건수 메시지), 없으면 SUCCEEDED(`rows_in` = 보낸 경보 수, `rows_out` = SCORED 수). 대상 조회·DB 오류로 멈추면 FAILED + 500, 이미 커밋한 묶음은 그대로. 앱이 죽어 남은 `RUNNING`은 기동 때 `FAILED`로.

- 대상: 그날 `PENDING`(재실행 땐 남은 `PENDING`과, `FAILED` 중 `score_failure_code`가 **NULL(묶음 실패)이거나 `COMPUTE_ERROR`**인 것 — 10/29) 경보를 **500개씩** `POST /v1/scores`. AI 호출은 트랜잭션 밖, **묶음마다 커밋**.
- 응답 처리 [10/29 준혁 확인, AI SCRUM-41]:
  - 점수가 있으면(`OK`·`SHORT_HISTORY`·`MISSING_INPUT`) `alert_scores` 한 행 + `SCORED`. 이력 부족·일부 결측도 **실패가 아니에요**.
  - 항목이 `FAILED`면 `score_status = FAILED` + `score_failure_code` 저장, `alert_scores` 행은 만들지 않아요. 없는 거래 ID도 그 경보만 이렇게 와요(HTTP 200, 나머지는 정상).
  - 응답에 그 `alert_id`가 없거나 묶음 호출이 실패하면(503 `MODEL_UNAVAILABLE`·`DB_UNAVAILABLE`은 1회 재시도 뒤) `FAILED` + 코드 NULL. JSON 형식 오류는 요청 전체 400.
  - `failure_code` 4값 — `TRANSACTION_NOT_FOUND`·`HISTORY_NOT_SUPPORTED`·`REQUIRED_INPUT_MISSING`은 다시 보내도 같은 결과라 재처리하지 않아요. `COMPUTE_ERROR`만 재처리.
- **FAILED는 저위험이 아니에요.** 0점·LOW로 바꾸지 않고, 케이스 생성 대상에서 빠지고, 지우지 않아요. [안건 4]
- `score_status`(백엔드 처리 상태)와 `data_quality_status`(AI 입력 품질: OK·SHORT_HISTORY·MISSING_INPUT·FAILED)는 **다른 값**이에요. 합치지 않아요. [ML 규약 §4]
- **`score_band`(HIGH/MEDIUM/LOW)는 AI가 주지 않아요.** AI는 `score`와 추천 `threshold`만 주고, 등급은 **cases의 `CasePolicy`**가 계산해요. [안건 2] 〔SD 명세 §13-8의 "등급 경계는 detection"은 그 전 안〕
- 요청·응답 모양은 ML 규약이 기준이에요: 요청 `{batch_run_id, alerts[]}`, 응답 `{batch_run_id, scores[], scoring_meta{model_version, feature_version, server_version, latency_ms}, produced_at}`. 〔SD 명세 §11.3·11.4의 배열 모양·항목별 `model_version`은 그 전 안〕
- 재시도는 별도 유형 없이 **점수 배치 재실행**. [안건 14·21] 〔SD 명세의 `/score/retry`·`SCORE_RETRY`는 그 전 안〕

**다른 기능에 주는 공개 유스케이스** 〔초안 — SD 명세 §10 + CASES 명세 §6.1의 추가 요청〕
| 유스케이스 | 부르는 쪽 | 하는 일 | 트랜잭션 |
|---|---|---|---|
| `FindAlertsForCaseOpening(batchDate)` | cases 케이스 생성 | `SCORED`·`case_opened = false` 경보 + 최신 점수. **HIGH로 미리 거르지 않음** — 분류는 CasePolicy [CASES 명세] | 읽기 |
| `MarkCaseOpened(alertId)` | cases 케이스 생성 | `case_opened = true`. 이미 true면 409 | **부르는 쪽 트랜잭션에 참여** (케이스 INSERT와 같이 성공·실패) |
| `GetAlertSummary(alertId)` | cases 상세 (`GetCaseDetail`) | 경보(`alert_no`·`rule_id`·`batch_date`·창·`reference_ts`) + 거래 수 + 점수(`score_status`·`score`·실패 코드·모델·피처 버전·`data_quality_status`·`reasons`(저장값 그대로, 없으면 `[]`)). 점수 묶음은 늘 있고, 점수 전·산출 실패면 `score_status`·실패 코드만 있고 점수·버전·`data_quality_status`가 null. `reasons_status`는 값 목록·reasons 형식이 미결(3·5번)이라 안 줌(지윤 요청). 등급·정책 버전은 없음 — cases가 생성 때 저장 [SCRUM-152] | 읽기 |
| `ListAlertTransactions(alertId, pageable)` | cases 거래 목록 | 근거 거래 페이지. 마스킹은 부르는 쪽 응답에서 | 읽기 |
| `FindAlertsByEntity(entityId, before)` | cases·str (`prior_alerts`) | 같은 법인의 과거 경보(`reference_ts` 이전만) | 읽기 |
| `GetAlertForDraft(alertId)` (명세 이름 GetAlertPayload) | str 초안 요청 (`RequestStrDraft`) | `AlertPayload`(규약 §2, `created_at` 포함) + `reasons`(점수 payload 그대로, 없으면 `[]`) + `prior_alert_count`(같은 법인·앞선 경보 수) [SCRUM-155] | 읽기 |
| ~~`RecordScoreBand`~~ | — | 안 만듦. 등급·정책 버전은 cases가 케이스 생성 때 자기 행에 저장(계약서 Q2, SCRUM-152) | — |

**관리자 API** 〔초안 — SD 명세〕 룰 스캔 `POST /admin/batches/rule-scan?date=`, 점수 `POST /admin/batches/score?date=`, 경보 점검 `GET /admin/alerts`(+`/{alert_no}`, `/{alert_no}/transactions` — 열람 기록), 룰 `GET /admin/rules`·`PUT /admin/rules/{rule_id}/params`(새 버전 행 추가 + 이전 행 비활성을 한 트랜잭션, `(rule_id, version)` unique로 409).

**〔확인 필요〕** 룰 스캔 날짜 범위(9/1~9/10만 일반 스캔?), 통화가 섞인 창의 `rule_facts`, 목업 점수 → 실제 모델 전환 때 재채점 방법, `typology_hint` null 허용·`rule_facts` 빈 객체 세칙(ML 규약 미확인 표).
