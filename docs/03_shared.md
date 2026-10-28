# 03. 공통 코드 (`shared/`)

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

`shared/`는 진희가 관리해요. 다른 사람이 바꿔야 하면 PR로 요청해요. [가이드 §3.3]

**공통 변경 공지** 〔제안〕 — `shared/`나 보안 설정이 바뀌면 팀원 전원 코드에 영향이 가요. 머지할 때 #개발에 아래 네 줄을 남겨요.

```
[공통 변경] 무엇이 바뀌나
- 팀원 코드에서 바꿀 것: (없으면 "없음")
- 언제부터: develop 머지 시점
- PR: #n
```

## 1. 엔티티 베이스 [레퍼런스 §8.1, 코드 `shared/domain`]

| 클래스 | 쓰는 곳 | 컬럼 |
|---|---|---|
| `BaseEntity` | 사람이 고치는 테이블 (`cases`, `str_drafts`, `users`, `rules` …) | `id`, `created_at`, `created_by`, `updated_at`, `updated_by` — 자동 채움 |
| `CreatedOnlyEntity` | 추가만 하는 테이블 (`case_history`, `str_revisions`, `approvals`, 로그 …) | `id`, `created_at`, `created_by` |
| (상속 없음, `@Immutable`) | 원천 4개 | 원천 컬럼 그대로 |

- 감사 컬럼은 `SessionAuditorAware`가 세션 사용자 ID로 채워요. 세션이 없으면(배치) `SYSTEM`(`users.id = 1`). [레퍼런스 §9.4, 코드 `CurrentUser.SYSTEM_ID = 1L`]
- 비동기 배치 스레드엔 세션이 없어서, 컨트롤러가 요청자 ID를 꺼내 유스케이스에 넘겨요(`triggered_by`). [SD 명세 §3 〔초안〕]

## 2. 에러 응답 [레퍼런스 §8.2, 코드 `ErrorResponse`·`GlobalExceptionHandler`]

프론트가 받는 에러는 모양이 하나예요.

```json
{ "error": { "code": "INVALID_CASE_TRANSITION",
             "message": "CASE-2022-000057: OPEN -> ESCALATED is not allowed",
             "details": { "case_no": "CASE-2022-000057", "from": "OPEN", "to": "ESCALATED" } } }
```

`details`가 없으면 `{}`예요. 〔확인 필요: 계정·권한 §5는 `{code, message, trace_id}` 모양으로 적혀 있는데, 계정·권한 문서 스스로 "다르면 레퍼런스가 맞다"고 해서 위 모양이 맞아요〕

| 예외 | HTTP | `code` |
|---|---|---|
| `@Valid` 실패, JSON 파싱 실패 | 400 | `INVALID_REQUEST` |
| `NotFoundException` 하위 | 404 | 하위 클래스가 정함 (`CASE_NOT_FOUND`, `DRAFT_NOT_FOUND` …) |
| `DomainException` 하위 전부 | **409** | 클래스 이름 UPPER_SNAKE (`INVALID_CASE_TRANSITION`, `SELF_APPROVAL` …) |
| 낙관적 락 충돌 | 409 | `CONCURRENT_UPDATE` |
| `AiClientException` | 502 | AI가 준 코드 그대로. **예외:** AI 409 `GENERATION_IN_PROGRESS`는 `AiClient`가 409 `DRAFT_IN_PROGRESS`로 바꿔 보내요("생성 중" ≠ AI 장애) [10/29] |
| 권한 없음 | 403 | `FORBIDDEN` |
| 그 밖 | 500 | `INTERNAL_ERROR`, 메시지는 "알 수 없는 오류" 고정, 스택은 로그에만 |

- 상태 전이 에러 이름은 **`INVALID_CASE_TRANSITION`으로 통일**(10/28). 옛 표기 `INVALID_TRANSITION`은 쓰지 않아요. [STR 문서 미결, CASES 명세 §4]
- 동시 수정 409 이름은 **`CONCURRENT_UPDATE`**예요. 계정·권한 §5의 `CONFLICT`는 옛 표기예요. [레퍼런스 §8.2, CASES·STR 명세]

### 지금까지 문서에 나온 에러 코드 (한곳에 모음)

| HTTP | code | 어디서 | 상태 |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 공통 (반려 코드·사유 누락 포함) | 확정 |
| 401 | `UNAUTHENTICATED`, `LOGIN_FAILED` | 인증 | 확정 [계정·권한 §3] |
| 403 | `FORBIDDEN`, `CSRF_INVALID`, `NOT_ASSIGNEE` | 권한 | 확정 [계정·권한 §5] |
| 404 | `CASE_NOT_FOUND`, `DRAFT_NOT_FOUND`, `USER_NOT_FOUND`, `EVIDENCE_NOT_FOUND`, `BATCH_RUN_NOT_FOUND`, `ALERT_NOT_FOUND`, `RULE_NOT_FOUND` | 각 기능 | 〔초안〕 |
| 409 | `INVALID_CASE_TRANSITION`, `CONCURRENT_UPDATE`, `SELF_APPROVAL`, `NARRATIVE_UNCHANGED`, `BATCH_ALREADY_DONE`, `BATCH_RUNNING` | 공통·cases·str·batch | 확정 [안건 24, 레퍼런스 §11.1] |
| 409 | `DRAFT_NOT_VERIFIED`, `DRAFT_LOCKED`, `DRAFT_SUPERSEDED`, `DRAFT_IN_PROGRESS`, `VERIFY_IN_PROGRESS` | str | 〔초안〕 STR 명세 |
| 409 | `GENERATION_IN_PROGRESS` → 프론트엔 `DRAFT_IN_PROGRESS` | AI가 주는 409. 자동 재시도 안 함, `AiClient`가 409로 그대로 전달 | 확정 [안건 23, 10/29] |
| 409 | `INVALID_ASSIGNEE`, `CLOSE_REQUIRES_STR`, `POLICY_NOT_CONFIGURED` | cases | 〔초안〕 CASES 명세 "신규 제안" |
| 400·409 | `DATE_OUT_OF_RANGE`, `INVALID_RULE_PARAMS`, `DATASET_NOT_LOADED`, `NO_ACTIVE_RULES`, `DATASET_FILES_MISSING`, `RULE_SCAN_NOT_DONE`, `SCORE_NOT_DONE` | source·detection | 〔초안〕 SD 명세 "신규 제안" |
| 502 | AI가 준 코드 그대로 (`LLM_UNAVAILABLE`, `LLM_INVALID_OUTPUT`, `LLM_BUDGET_EXCEEDED`, AI 503 `MODEL_UNAVAILABLE`), `AI_TIMEOUT`, `AI_BAD_RESPONSE` | AI 연동 | AI 코드는 확정 [규약 v0.2 §6.3], `AI_*`는 〔초안〕 |

AI 쪽 코드 메모 [규약 v0.2 §6.3]: `LLM_TIMEOUT`은 v0.2에서 없어지고 `LLM_UNAVAILABLE`로 합쳐졌어요. `VERIFY_UNAVAILABLE`은 에러가 아니라 **검증 결과의 `status` 값**(200 응답)이라 검증 결과 한 줄로 저장해요 [§5.3].
| 500 | `INTERNAL_ERROR` | 공통 | 확정 |

"신규 제안" 코드는 진희가 확인한 뒤 `GlobalExceptionHandler`·문서에 넣어요. [SD 명세 §2.3]

〔제안〕 에러 코드는 지금 레퍼런스·계정·권한·각 명세에 흩어져 있고 이름도 조금씩 달랐어요(`CONFLICT`/`CONCURRENT_UPDATE`, `INVALID_TRANSITION`/`INVALID_CASE_TRANSITION`). **이 표 한 장**을 노션 「공통 설계」에 올리고, 새 코드는 표에 먼저 추가한 다음 코드에 넣어요. 프론트도 이 표 하나로 분기할 수 있어요.

## 3. 목록 응답과 API 공통 규칙 [레퍼런스 §12, CASES 명세 §4 〔초안〕]

| 항목 | 규칙 |
|---|---|
| 경로 | `/api/v1/{리소스 복수}/{번호}/{동사}`. 경로 번호는 **사람이 보는 번호**(`CASE-2022-000057`, `ALT-…`, `DRF-…`). bigint PK는 밖으로 안 나가요. 예외: 배치는 숫자 `batch_run_id`, 거래는 숫자 `transaction_id` |
| 상태 변경 | 항상 `POST …/{동사}`. PATCH로 `status`를 바꾸는 API 없음 |
| 동시 수정 | 변경 요청에 화면이 읽은 `version`을 보내요. 다르면 409 `CONCURRENT_UPDATE` (STR 쪽은 `case_version` 이름도 씀) |
| 페이지 | `page=0`, `size=20` 기본, `size` 최대 100(넘으면 400) |
| 정렬 | `sort=created_at,desc`. 허용 컬럼은 엔드포인트별 화이트리스트, 없는 컬럼 400. 동률은 내부 ID 보조 정렬 |
| 필터 | 쿼리 파라미터 (`status=OPEN,REVIEWING&assignee=me&from=…&to=…`) |
| 응답 코드 | 200 조회·변경, 201 생성, 202 배치 접수 |
| 문서 | springdoc, `local` 프로파일에서만 `/swagger-ui.html` |
| 상세 응답 | 화면 하나에 요청 하나 — 케이스 상세는 경보·점수·요약·초안·이력을 한 번에, 거래 목록만 따로 페이징 |

**목록 응답 모양** — `{ "items": [...], "page": 0, "size": 20, "total": 143 }`. 목록 API는 `PageResponse.from(page)`로 반환해요. 전체 페이지 수는 프론트가 `ceil(total / size)`로 계산해요. [레퍼런스 §12] 〔코드를 이 모양으로 맞추는 중 — SCRUM-39, 머지 전〕

**시각 표기** 〔제안〕 — 감사 시각은 UTC `Z`를 붙이고, 원천 거래 시각은 시간대 없이. `01_code_rules.md` §5.

## 4. 현재 사용자 [가이드 §6.3, 레퍼런스 §9.4, 코드 `CurrentUser`]

- 유스케이스는 `actorId`를 인자로 받지 않고 `CurrentUser` 빈에서 꺼내요. `require()`는 로그인 사용자, `idOrSystem()`은 배치용.
- 요청 body에 `user_id`·`created_by` 같은 필드를 받는 API는 하나도 없어요. ADMIN이 남에게 배정할 때의 `assignee`는 **작업자가 아니라 업무 입력**이라 예외예요. [CASES 명세 §4]

## 5. `AiClient` — AI 서버 호출 [가이드 §5, 레퍼런스 §8.4·§8.5]

유스케이스는 `AiClient` 인터페이스만 봐요. 구현(`WebClientAiClient`)과 `ai_call_logs` 기록은 진희가 만들어요(SCRUM-17·18).

| 메서드 | AI 엔드포인트 | 부르는 쪽 |
|---|---|---|
| `scores(alerts)` | `POST /v1/scores` — 500개씩 나눠 보냄 | detection `RequestScores` |
| `createDraft(request)` | `POST /v1/str/drafts` | str `RequestStrDraft` |
| `verify(request)` | `POST /v1/str/verify` | str `VerifyStrDraft` |
| `caseSummary(request)` | `POST /v1/cases/summary` | cases `RefreshCaseSummary` |
| `health()` | `GET /v1/health` — 기동 시 1회, 실패해도 기동은 함 | `AiHealthCheck` |

**공통 동작**
- 헤더 `X-Internal-Key`(32자 이상 난수, 환경변수), `X-Request-Id`(UUID, `ai_call_logs.request_id`와 같은 값).
- **시간 한도** — 초안·검증·요약처럼 **화면에서 부르는 요청은 전체 90초**(재시도 포함, 호출마다 90초가 아님). 점수 배치는 **500건 묶음 호출 하나마다** 같은 규칙이에요(배치 전체가 90초가 아님). MVP는 **동기**(`@Async`·큐·폴링은 고도화). [10/29]
- 4xx는 재시도 없이 `AiClientException` → 502. 단 409 `GENERATION_IN_PROGRESS`는 409 `DRAFT_IN_PROGRESS`로. [10/29]
- **재시도** — 최대 **1회**, 그리고 **위 시간 한도 안에 남은 시간이 있을 때만**. [10/29]
  - 다시 보내는 것: AI가 **바로 돌려준** `LLM_UNAVAILABLE`·`MODEL_UNAVAILABLE`(AI 쪽 연결 실패), 백엔드가 AI에 **연결 자체를 못 한** 경우(연결 거부 등).
  - 다시 보내지 않는 것: **백엔드 쪽 응답 대기 타임아웃**(시간을 이미 다 썼고, AI가 아직 처리 중일 수 있음), 4xx·`LLM_INVALID_OUTPUT`·`LLM_BUDGET_EXCEEDED`·`GENERATION_IN_PROGRESS`.
  - 대상 코드는 규약 v0.2 §6.2 그대로이고, "남은 시간 안에서만"과 "백엔드 타임아웃은 제외"를 10/29에 더했어요. 규약 문구도 다음 규약 변경 때 맞출게요. 〔레퍼런스 §8.5·SD 명세의 "5xx·타임아웃 1회"는 그 전 표기〕
- AI 쪽 중첩 재시도 금지. [안건 21]
- 모든 호출은 `ai_call_logs`에 한 줄 — **메타데이터만**: 엔드포인트, `request_id`, 상태 코드, 에러 코드, 소요 ms, 재시도 회차, 버전, `generation_id`. **요청·응답 본문은 실패해도 저장하지 않아요.** 실패 요청에 조사 메모·보고서 본문이 들어갈 수 있어서예요. 전문은 **AI가 그 요청을 저장했을 때만** AI 쪽 `ai.generations`에서 `request_id`로 찾을 수 있어요(연결 실패·인증 거부처럼 AI가 기록 전에 끝난 요청은 전문이 없어요). [규약 v0.2 §6.4, 10/29] 〔레퍼런스 §3.4의 "실패한 호출만 본문 저장"은 그 전 안〕
- `AiDtos`는 규약 v0.2 이전 뼈대예요. 실제 연결 전에 `draft_id`·`generation_id`·검증 요청·응답 모양으로 바꿔요(SCRUM-17).

**트랜잭션 패턴 — "DB → HTTP → DB"** 네 군데 [레퍼런스 §8.4]
1. 점수 요청: `RequestScores` (500개 묶음마다 커밋)
2. 케이스 요약: `RefreshCaseSummary`
3. STR 초안: `RequestStrDraft`(트랜잭션 없음) → `SaveStrDraft`(트랜잭션)
4. STR 검증: `VerifyStrDraft`

공통 규칙: AI 응답을 기다리는 동안 DB 트랜잭션·락을 잡지 않아요. 저장할 때 **상태·담당자·version을 다시 확인**해서, 그사이 케이스가 종료됐으면 늦게 온 응답은 저장하지 않아요. [안건 11, CASES 명세 §8.3 〔초안〕]

## 6. 배치 실행 기록 (`BatchRun`) [레퍼런스 §11, 안건 14·21, 코드 `V004`·`shared/batch`]

| 배치 | API | 기능 폴더 | `batch_type` (코드) |
|---|---|---|---|
| 적재 | `POST /api/v1/admin/batches/load` | source | `LOAD_DATASET` |
| 룰 스캔 | `POST /api/v1/admin/batches/rule-scan?date=` | detection | `RULE_SCAN` |
| 점수 요청 | `POST /api/v1/admin/batches/score?date=` | detection | `SCORE_REQUEST` |
| 케이스 생성 | `POST /api/v1/admin/batches/open-cases?date=` | cases | `OPEN_CASES` |
| 상태 조회 | `GET /api/v1/admin/batches/{batch_run_id}`, 목록 `GET /api/v1/admin/batches` | shared | — |

- 전부 **관리자가 API로 직접 실행**, 스케줄러 없음. 응답은 `batch_runs` 한 행. 적재는 `@Async` + 202, 룰 스캔·점수는 동기. [레퍼런스 §11] 〔초안: CASES 명세는 케이스 생성도 202 비동기 제안〕
- `batch_runs` 상태 기록은 본 작업과 **다른 트랜잭션**으로 커밋해요. 본 작업이 롤백돼도 "실패했다"는 기록이 남게. [SD 명세 §12 〔초안〕]
- 앱이 배치 도중 죽으면 `RUNNING`이 남아요. 기동 시 오래된 `RUNNING`을 `FAILED`로 정리해요. [SD 명세 §12 〔초안〕, CASES 명세 §8.3]

**재실행 규칙 (확정)** [안건 14·21]
| 배치 | 성공한 날짜를 다시 돌리면 | 실행 중이면 |
|---|---|---|
| 적재·룰 스캔 | 409 `BATCH_ALREADY_DONE` | 409 `BATCH_RUNNING` |
| 점수·케이스 생성 | **허용** — 남은 대상(미처리·실패)만 처리, 새 `BatchRun` 행 | 409 `BATCH_RUNNING` |

- 점수 재시도는 별도 유형(`SCORE_RETRY`) 없이 **점수 배치 재실행**으로 해요. [안건 14·21] 〔SD 명세 v0.1의 `SCORE_RETRY`·`/score/retry` 제안은 회의 결정 이전 안〕
- 〔확인 필요: 지금 `V004`의 partial unique 인덱스는 네 배치 전부 "성공 후 재실행 금지"라, 새 마이그레이션에서 `LOAD_DATASET`·`RULE_SCAN`으로 좁혀야 해요. 적재는 원천 전체를 한 번 넣는 작업이라 **날짜와 상관없이** 성공 1회·실행 1개로 막아야 해요(적재일이 다르면 같은 키가 아님). 멈춘 `RUNNING`을 정리한 뒤 이전 작업이 늦게 끝나도 새 실행을 덮어쓰지 않는 조건도 필요해요(노션 미결 4번). SD 명세는 조건에 `RUNNING`도 넣자고 제안. `batch_type` 값도 회의 문서는 `SCORE`, 코드는 `SCORE_REQUEST` — 코드 값을 유지하는 방향. 부분 성공 상태(`PARTIAL`)와 `metrics jsonb` 컬럼 제안도 미결〕

## 7. 사람이 보는 번호 (`DisplayNumber`) [레퍼런스 §8.7]

| 번호 | 형식 | 시퀀스 |
|---|---|---|
| 경보 | `ALT-{배치일 YYYYMMDD}-{6자리}` | `ops.alert_no_seq` (V005) |
| 케이스 | `CASE-{경보 기준 연도}-{6자리}` | `ops.case_no_seq` (V006) |
| 초안 | `DRF-…` — 백엔드가 AI 호출 **전에** 시퀀스로 먼저 발급 [안건 23] | 〔초안: 형식·시퀀스는 STR 쪽에서 정함〕 |

- 시퀀스는 날짜별로 리셋하지 않아요. `MAX(id)+1` 방식은 금지(두 트랜잭션이 같은 번호를 받을 수 있음).
- 〔미결: 확정 보고서 번호 `STR-{연도}-{6자리}` — 서식 안건 3·5-2〕

## 8. 로깅·요청 추적 [레퍼런스 §8.6, 코드 `RequestIdFilter`·`UserIdMdcFilter`]

- `request_id`·`user_id`를 MDC에 넣어 모든 로그에 붙여요. AI 호출 땐 같은 값을 `X-Request-Id`로 보내서 AI 로그와 짝을 맞춰요.
- 로그 금지 항목은 `01_code_rules.md` §6.

## 9. 마스킹 [레퍼런스 §10-2]

- API 응답의 계좌번호는 **뒤 4자리만** (`****9D80`). 응답 record에서 처리. 전체 번호는 ADMIN 내보내기에만.
- 〔확인 필요〕 `account_key`(`{bank_id}_{account_no}`)를 그대로 보내면 `account_no`를 마스킹해도 전체 번호가 보여요. 프론트 응답에서 빼거나 마스킹할지, 화면에서 계좌를 무엇으로 가리킬지 정해야 해요(프론트 API 계약서 SCRUM-21). 지금은 합성 데이터라 당장 위험은 없지만, 실데이터 기준으로 정해 둬요. → [노션 미결](https://app.notion.com/p/4077fbce53048094a838f49ca1bde5d4) 1번
