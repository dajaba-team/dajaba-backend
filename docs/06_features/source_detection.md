# 06. source · detection — 적재 · 룰 · 경보 · 점수

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 담당 준혁 · 정리 진희

담당자 문서는 아직 초안이라 `확정` / **〔초안〕** / **〔확인 필요〕**를 나눠 적었어요(뜻은 [`../README.md`](../README.md) "읽는 법"). 이 파일은 **담당자가 고쳐요.** 노션 초안을 바꾸면 같은 주에 이 파일도 PR로 갱신해 주세요.

## source — 적재 (준혁)

**하는 일** — IBM HI-Small CSV 3개(`HI-Small_Trans.csv`, `HI-Small_accounts.csv`, `HI-Small_Patterns.txt`)를 원천 4개 테이블에 **한 번** 적재해요. 이후 원천은 읽기 전용이고, AI 서버(`app_ai`)도 이 4개를 직접 읽어요. [레퍼런스 §11.2, SD 명세 §1]

**확정**
- 원천 4개는 적재 후 UPDATE·DELETE 없음. 적재는 빈 DB에서만(이미 있으면 409). [레퍼런스 §11.1]
- `is_laundering`·`pattern_type`·`pattern_id`는 적재하지만 **백엔드 엔티티에 매핑하지 않고 응답에도 없어요.** 적재 SQL 안에서 검증용으로만 세요. [레퍼런스 §10-10, SD 명세 §3]
- 적재는 JPA가 아니라 `CopyManager`(COPY)로. `transactions_staging`(unlogged)을 거쳐 `INSERT … SELECT`로 변환. [레퍼런스 §11.2]
- 서비스 DB에는 **HI-Small만** 들어가요(SAML-D·LI는 학습 전용). 준영이 준 검증값(`expected.json`)으로 적재 결과를 같이 확인해요. [안건 12·13]
- 금액은 `numeric(18,2)` 유지. [안건 19]
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

**검증값** 〔초안 — SD 명세 §3, 실측 후 확정〕
| 항목 | 값 |
|---|---|
| 거래 수 | 5,078,345 |
| 세탁 거래 수 | 5,177 |
| 자기계좌 거래 수 | 591,212 |
| 기간 | 2022-09-01 00:00 ~ 2022-09-18 16:18 (실질 9/1~9/10) |
| 법인 수 | 166,207 |
| 계좌 수 | 518,573 또는 518,581 — 〔확인 필요〕 서식 안건 5의 10/28 실측은 계좌 518,581행 |
| 패턴 사건 / 패턴 거래 | 370 / 3,209 (재확인 필요) |

**〔확인 필요〕**
- **은행 ID 앞자리 0** — 거래 파일은 `023842`, 계좌 파일은 `23842`처럼 달라서, 그대로 이으면 계좌 연결이 **100% 실패**하고 앞 0을 빼면 100% 연결돼요(10/28 전체 검증). [서식 안건 5-1] 그런데 SD 명세 §11.2·ML 규약 §0은 "`account_key`는 앞 0 보존"이라고 적혀 있어요. **어느 쪽 형식으로 맞출지** 정해야 경보 화면부터 이름이 나와요. 준혁
- **통화 표기** — DB엔 IBM 원문 이름(`US Dollar`)이 들어가요. 규약 v0.2의 STR facts는 ISO 코드(`USD`)라 AI 서버가 읽을 때 바꿔요. 백엔드 화면·`rule_facts`의 통화를 원문으로 둘지 코드로 바꿀지, 매핑표를 누가 관리할지 정해야 해요. [CASES 명세 §4는 "SOURCE 매핑표를 따른다"]
- 서식 반영 — `entity_type` 6종 → 서식 표기(5-6), `accounts.bank_country`(5-10), `customer_attributes`를 508만 건 전체 계좌에 만들지(5-7). 서식 회의 전.

**API** — `POST /api/v1/admin/batches/load`(ADMIN, 202), 상태는 `GET /api/v1/admin/batches/{batch_run_id}`. 실패 응답: 이미 적재됨 409 `BATCH_ALREADY_DONE`, 실행 중 409 `BATCH_RUNNING`, CSV 없음 409 `DATASET_FILES_MISSING`〔초안〕.

---

## detection — 룰·경보·점수 (준혁)

**하는 일** — 날짜별로 룰을 돌려 `alerts`를 만들고, AI에 점수를 받아 `alert_scores`에 저장해요. 사람이 고치는 건 `rules`의 기준값뿐이에요. [가이드 §1]

**테이블** [레퍼런스 §3.2]
- `rules` — `rule_id`(`R02_FAN_IN_24H`), `version`(기준값 바뀔 때마다 +1, `(rule_id, version)` unique), `name`, `typology_hint`, `params jsonb`, `is_active`. 감사 4. 끈 룰도 행은 살려 둬요(과거 경보가 가리킴).
- `alerts` — `alert_no`(unique), `batch_run_id`, `rule_ref_id`(어느 버전의 룰), `account_key`, `entity_id`(null 가능), `window_start`, `window_end`, `reference_ts`, `rule_facts jsonb`, `batch_date`, `score_status`(PENDING/SCORED/FAILED), `score_failure_code`(null 가능 — FAILED일 때 AI `failure_code`, 10/29), `case_opened`. unique `(rule_ref_id, account_key, window_start)`. 만든 뒤 바뀌는 건 `score_status`·`score_failure_code`·`case_opened`뿐.
- `alert_transactions` — 경보 ↔ 거래 N:M, 복합 PK.
- `alert_scores` — `score`(null 가능), `threshold`(AI 추천값), `model_version`, `feature_version`, `prediction_id`, `produced_at`, `payload jsonb`(응답 원문 — `data_quality_status`도 여기서 읽어요, 10/29 준혁). **MVP는 `alert_id` UNIQUE 유지**, 다른 모델로 재채점은 고도화. [안건 2·4·5·21, ML 규약 §6.3] 〔레퍼런스·SD 명세의 "경보 하나에 점수 여러 행"은 그 전 안〕

**룰 8개** [안건 20, 규약 §2]
| 번호 | 룰 | 지금 |
|---|---|---|
| R01 | `R01_FAN_OUT_24H` — 서로 다른 수신 계좌 N개 이상으로 송금 | 기준값 스윕 후 |
| R02 | `R02_FAN_IN_24H` — 서로 다른 송신 계좌 N개 이상에서 입금 | **대표 룰, 먼저 구현** |
| R03 | `R03_PASS_THROUGH` — 입금 후 T시간 내 P% 이상 출금 | |
| R04 | `R04_CYCLE` — 자기 계좌로 돌아오는 고리 | **최대 3홉**, `rule_facts.paths` 필수 |
| R05 | `R05_STACK` — 연속 경유 | **유지**, `rule_facts.paths` 필수, 수치 한도 미정 |
| R06 | `R06_BIPARTITE` | **교체 — 대체 룰 미정** (준혁) |
| R07 | `R07_STRUCTURING` — 기준 금액 바로 아래 반복 | |
| R08 | `R08_NEW_ACCOUNT_LARGE` — 개설 직후 거액 | 필요 데이터·기준 확정 필요 |

**룰 공통 네 가지** [안건 20]: ① 룰 ID는 규약 번호 그대로(코드 enum·DB·학습 데이터 같은 문자열) ② 집계는 `amount_paid`·`currency_paid` 기준, **통화별로 따로**(환산 없음) ③ 시간창 기본은 **달력일**, `reference_ts` = 창의 끝 ④ **자기계좌 거래 제외**. 룰 명세는 준혁이 관리하고, 준영의 학습용 경보와 `expected.json`으로 건수·거래 ID를 맞춰 봐요.

**`rule_facts.paths` (R04·R05)** [ML 규약 §2.4] — 경로별 실제 거래 ID의 시간순 배열(`[[…],[…]]`). 합집합 = `transaction_ids`. R04는 모든 고리가 대표 계좌에서 시작해 같은 계좌로 돌아와요. 경로를 임의로 재배열·삭제하지 않아요.

**룰 엔진 = 두 단계** [레퍼런스 §6.1 ③]
1. **집계는 SQL**(네이티브 쿼리): "수신 계좌·일자별 서로 다른 송신 계좌 수와 거래 ID 목록"
2. **판정은 Java**: `FanInRule.evaluate(AggregatedRow) → Optional<AlertCandidate>`. 기준은 이상(≥). 기준값이 없거나 0 이하면 `InvalidRuleParamsException` — 기본값으로 조용히 돌지 않아요. TDD 대상은 2단계.

**룰 스캔** [레퍼런스 §11.3, SD 명세 §5 〔초안〕] — 룰 전체가 **한 트랜잭션**(하나라도 실패하면 그 실행의 경보 전부 롤백 → 다시 돌려도 두 번 생기지 않음). `alert_no`는 시퀀스, `score_status = PENDING`, `case_opened = false`. 경보 단위는 계좌 × 룰 × 하루 창(00:00:00~23:59:59) — 이름은 "24H"지만 자정에 걸친 패턴은 못 잡아요(받아들인 한계).

**점수 요청** [레퍼런스 §11.1, 안건 2·4·14·21, ML 규약 §2·§3·§6]
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
| `GetAlertSummary(alertId)` | cases 상세 | 경보 + 최신 점수 + 근거 수 | 읽기 |
| `ListAlertTransactions(alertId, pageable)` | cases 거래 목록 | 근거 거래 페이지. 마스킹은 부르는 쪽 응답에서 | 읽기 |
| `FindAlertsByEntity(entityId, before)` | cases·str (`prior_alerts`) | 같은 법인의 과거 경보(`reference_ts` 이전만) | 읽기 |
| `GetAlertPayload(alertId)` | str 초안 요청 | 규약 2절 경보 JSON + 최신 점수 | 읽기 |
| `RecordScoreBand(scoreId, band, policySnapshot)` | cases | detection이 자기 점수 행에 등급·정책 결과 저장 — **CASES 명세 추가 제안** | — |

**관리자 API** 〔초안 — SD 명세〕 룰 스캔 `POST /admin/batches/rule-scan?date=`, 점수 `POST /admin/batches/score?date=`, 경보 점검 `GET /admin/alerts`(+`/{alert_no}`, `/{alert_no}/transactions` — 열람 기록), 룰 `GET /admin/rules`·`PUT /admin/rules/{rule_id}/params`(새 버전 행 추가 + 이전 행 비활성을 한 트랜잭션, `(rule_id, version)` unique로 409).

**〔확인 필요〕** 룰 스캔 날짜 범위(9/1~9/10만 일반 스캔?), 통화가 섞인 창의 `rule_facts`, 목업 점수 → 실제 모델 전환 때 재채점 방법, `typology_hint` null 허용·`rule_facts` 빈 객체 세칙(ML 규약 미확인 표).
