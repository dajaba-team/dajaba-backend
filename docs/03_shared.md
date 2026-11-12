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
| `AuditedEntity` | 직접 상속하지 않음 — 아래 `BaseEntity`·`AssignedIdEntity`의 부모 [SCRUM-68] | `created_at`, `created_by`, `updated_at`, `updated_by` — 자동 채움 (id 없음) |
| `BaseEntity` | 사람이 고치는 테이블 (`cases`, `users`, `rules` …) — `AuditedEntity` + DB 자동 발급(IDENTITY) `id` | `id`, `created_at`, `created_by`, `updated_at`, `updated_by` — 자동 채움 |
| `AssignedIdEntity` | 시퀀스로 **미리 받은 번호**를 id로 쓰는 테이블 (`str_drafts`, `str_draft_requests`) — `AuditedEntity` + 생성자로 받는 `id`. 새 행 판단은 `Persistable` [SCRUM-68] | `id`, `created_at`, `created_by`, `updated_at`, `updated_by` — 감사 4칸 자동 채움 |
| `CreatedOnlyEntity` | 사람이 추가만 하는 테이블 (`approvals` …). `case_history`·`str_revisions`는 예외 — 아래 | `id`, `created_at`, `created_by` |
| (상속 없음, `@Immutable`) | 원천 4개 | 원천 컬럼 그대로 |
| (상속 없음) | 운영 로그 (`ai_call_logs`, `batch_runs`) — `created_by` 없이 `created_at`만 [레퍼런스 §3.4] | 테이블 컬럼 그대로 |

`case_history`는 추가만 하지만 `CreatedOnlyEntity`를 상속하지 않아요. `created_by`가 로그인한 사람이 아니라 업무상 행위자(승인자, 생성 배치는 SYSTEM)라서 업무 메서드가 받은 값을 그대로 넣어요(SCRUM-98). `str_revisions`도 같은 이유로 상속하지 않아요 — 수정본 1번(AI 원문)은 SYSTEM, 그 뒤는 수정자를 업무 메서드가 직접 넣어요(미결 25).

- **`AssignedIdEntity`** — id를 직접 넣은 엔티티로 `save()`를 부르면 Spring Data가 "이미 있는 행"으로 보고 `merge`(SELECT 한 번 더, 같은 id면 조용히 덮어씀)로 가요. 그래서 `Persistable.isNew()`로 "만들고 아직 저장 안 함 = 새 행"을 알려 줘요 → INSERT 한 번, 같은 id를 또 새로 만들어 저장하면 PK 위반. 생성자에 null을 넣으면 `IllegalArgumentException`. 낙관적 락이 필요한 테이블(`str_drafts`)은 하위 엔티티에 `@Version`을 따로 둬요.
- id 컬럼 이름이 `id`가 아니면 하위 엔티티에 `@AttributeOverride`를 붙여요:
  ```java
  @Entity @Table(name = "str_draft_requests")
  @AttributeOverride(name = "id", column = @Column(name = "draft_id"))
  ```
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
| `@Valid` 실패, JSON 파싱 실패, 쿼리·경로 파라미터 오류(`details.parameter`에 틀린 파라미터 이름) | 400 | `INVALID_REQUEST` — `@Valid` 실패(본문·`@ModelAttribute` 쿼리)는 `details`에 **요청 JSON 이름(snake_case)** → 메시지, 중첩은 `items[0].transaction_id`, 한 필드에 위반이 여럿이면 하나만. 클래스 수준 검증(global error)은 지금 없어서 담지 않음. `details.parameter`는 `@RequestParam`·`@PathVariable`에 이름이 있으면 그 이름 [SCRUM-167] |
| `InvalidRequestException` (`shared/domain`) — `@Valid`로는 못 보고 지금 값과 비교해야 아는 입력 오류 | 400 | `INVALID_REQUEST` — `details`는 던진 쪽이 준 `{요청 JSON 이름: 메시지}` 그대로. 예: `User.changeRole`에서 조사역 ↔ 승인자가 아님(같은 역할·관리자에서 등) → `details.role`. `shared/domain`이라 엔티티도 던질 수 있음 [SCRUM-135 ②] |
| `NotFoundException` 하위 | 404 | 하위 클래스가 정함 (`CASE_NOT_FOUND`, `DRAFT_NOT_FOUND` …) |
| `ForbiddenException` 하위 | **403** | 클래스 이름 UPPER_SNAKE (`NOT_APPROVER`, `NOT_ASSIGNEE` …) [SCRUM-67] |
| `DomainException` 하위 전부 (위 둘 제외) | **409** | 클래스 이름 UPPER_SNAKE (`INVALID_CASE_TRANSITION`, `SELF_APPROVAL` …) |
| 낙관적 락 충돌 | 409 | `CONCURRENT_UPDATE` |
| `AiClientException` | 502 | AI가 준 코드 그대로. **예외:** AI 409 `GENERATION_IN_PROGRESS`는 `AiClient`가 409 `DRAFT_IN_PROGRESS`로 바꿔 보내요("생성 중" ≠ AI 장애) [10/29]. 백엔드 자체 코드와 이름이 같은 AI 4xx 셋은 `AI_`를 붙여 502로 — 아래 줄 [SCRUM-128] |
| AI 404 `DRAFT_NOT_FOUND` (AI가 `generation_id`를 모름) | 502 | `AI_DRAFT_NOT_FOUND` — 백엔드 404 `DRAFT_NOT_FOUND`(초안 없음)와 다른 뜻. 백엔드엔 초안이 있는데 AI가 생성본을 모름 = 데이터 어긋남 [SCRUM-128] |
| AI 400 `INVALID_REQUEST` (백엔드가 AI에 잘못 보냄) | 502 | `AI_INVALID_REQUEST` — 사용자 입력 400 `INVALID_REQUEST`와 다른 뜻(백엔드 쪽 버그) [SCRUM-128] |
| AI 401 `UNAUTHENTICATED` (`X-Internal-Key` 틀림) | 502 | `AI_UNAUTHENTICATED` — 로그인 401 `UNAUTHENTICATED`와 다른 뜻(서버 설정 문제, 다시 로그인해도 안 풀림) [SCRUM-128] |
| 권한 없음 | 403 | `FORBIDDEN` |
| 없는 경로 (`NoResourceFoundException`, 로그인한 `/api/**` 요청) | 404 | `NOT_FOUND` — "요청한 주소가 없습니다", `details` `{}`(경로를 다시 넣지 않음), 경고 한 줄(`not found method=… uri=…`) [SCRUM-157] |
| 안 되는 메서드 (`HttpRequestMethodNotSupportedException`) | 405 | `METHOD_NOT_ALLOWED` — "지원하지 않는 요청 방식입니다", `details` `{}`, `Allow` 헤더에 허용 메서드(Spring이 모르면 헤더 없음), 경고 한 줄(`method not allowed method=… uri=…`) [SCRUM-157] |
| 그 밖 (위 어디에도 안 걸린 진짜 처리 안 된 예외만) | 500 | `INTERNAL_ERROR`, 메시지는 "알 수 없는 오류" 고정, 스택은 로그에만 |

`/api/**` 밖은 `denyAll`이라 없는 경로여도 로그인 403 `FORBIDDEN`·비로그인 401 `UNAUTHENTICATED`예요. `/api/**` 안에서도 비로그인이면 404보다 401이 먼저예요(없는 주소인지 알려 주지 않음). [SCRUM-157]

- **업무 규칙 중 권한 문제(역할·담당자)는 `ForbiddenException`을 상속 → 403**, 코드는 클래스 이름 규칙 그대로(`NotApproverException` → `NOT_APPROVER`). **상태·버전 충돌은 `DomainException` → 409**(`SELF_APPROVAL`도 409 그대로). URL·역할 단위로 Spring Security가 막는 403은 따로(`FORBIDDEN`). [SCRUM-67, 백엔드 #22 리뷰 11/1]

- 상태 전이 에러 이름은 **`INVALID_CASE_TRANSITION`으로 통일**(10/28). 옛 표기 `INVALID_TRANSITION`은 쓰지 않아요. [STR 문서 미결, CASES 명세 §4]
- 동시 수정 409 이름은 **`CONCURRENT_UPDATE`**예요. 계정·권한 §5의 `CONFLICT`는 옛 표기예요. [레퍼런스 §8.2, CASES·STR 명세]

### 지금까지 문서에 나온 에러 코드 (한곳에 모음)

| HTTP | code | 어디서 | 상태 |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 공통 (반려 코드·사유 누락 포함) | 확정 |
| 401 | `UNAUTHENTICATED`, `LOGIN_FAILED` | 인증 — `LOGIN_FAILED`는 없는 ID·틀린 비밀번호·비활성·비밀번호 UTF-8 72바이트 초과 모두 같은 응답(04 §3·§7). `UNAUTHENTICATED`는 관리자가 역할 변경·비활성화·초기화로 끊은 세션의 다음 요청도(`UserSessions`) | 확정 [계정·권한 §3, SCRUM-135 ②-b] |
| 403 | `FORBIDDEN`, `CSRF_INVALID`, `NOT_ASSIGNEE`, `NOT_APPROVER` | 권한 (`NOT_ASSIGNEE`·`NOT_APPROVER`는 `ForbiddenException` 하위) | 확정 [계정·권한 §5, SCRUM-67] |
| 404 | `NOT_FOUND` | 공통 — 없는 경로(`NotFoundException` 하위가 아님) | 확정 [SCRUM-157] |
| 405 | `METHOD_NOT_ALLOWED` | 공통 — 안 되는 메서드, `Allow` 헤더 | 확정 [SCRUM-157] |
| 404 | `CASE_NOT_FOUND`, `USER_NOT_FOUND`, `ALERT_NOT_FOUND`, `BATCH_RUN_NOT_FOUND` | cases·user·detection·batch (`NotFoundException` 하위) | 확정 [예외 클래스 — `CaseNotFoundException`·`UserNotFoundException`·`AlertNotFoundException`·`BatchRunNotFoundException`] |
| 404 | `DRAFT_NOT_FOUND`, `EVIDENCE_NOT_FOUND`, `RULE_NOT_FOUND` | 각 기능 | 〔초안〕 |
| 404 | `REVISION_NOT_FOUND` | str | 확정 [계약서 Q13, 11/4 채영] |
| 409 | `INVALID_CASE_TRANSITION`, `CONCURRENT_UPDATE`, `SELF_APPROVAL`, `NARRATIVE_UNCHANGED`, `BATCH_ALREADY_DONE`, `BATCH_RUNNING` | 공통·cases·str·batch | 확정 [안건 24, 레퍼런스 §11.1] |
| 409 | `DRAFT_NOT_VERIFIED`, `DRAFT_LOCKED`, `DRAFT_IN_PROGRESS`, `VERIFY_IN_PROGRESS` | str | 〔초안〕 STR 명세 |
| 409 | `DRAFT_SUPERSEDED` (`details.draft_no`·`current_draft_no`) | shared `DraftSupersededException` — 요청 초안이 재생성으로 대체됨(케이스에 다른 현재 초안이 있음). S3·S6·P2·P3은 cases가 케이스를 잠근 뒤 던짐. 현재 초안이 없으면 `INVALID_CASE_TRANSITION` | 확정 [계약서 3절, SCRUM-223] |
| 409 | `DRAFT_REQUEST_CLOSED` | str `SaveStrDraft` — 끝난(만료·실패·성공) 초안 요청에 AI 응답이 늦게 옴. 저장하지 않음 | 확정 [SCRUM-154, 미결 21 ⑤] |
| 409 | `REPORT_PDF_NOT_READY` (`details.pdf_status` `PENDING`·`FAILED`) | str — 확정본 PDF가 아직 없음 | 확정 [계약서 Q7, 11/4 채영] |
| 409 | `GENERATION_IN_PROGRESS` → 프론트엔 `DRAFT_IN_PROGRESS` | AI가 주는 409. 자동 재시도 안 함, `AiClient`가 409로 그대로 전달 | 확정 [안건 23, 10/29] |
| 409 | `INVALID_ASSIGNEE`, `CLOSE_REQUIRES_STR`, `POLICY_NOT_CONFIGURED` | cases — `INVALID_ASSIGNEE`는 `details.reason` = `NOT_ACTIVE_INVESTIGATOR`(비활성·조사역 아님) · `SELF_ONLY`(조사역이 남을 지정). `CLOSE_REQUIRES_STR`은 초안 있는 케이스는 close-with-draft로. `POLICY_NOT_CONFIGURED`는 운영 등급 경계 없음 — LOW로 대신하지 않음 | 확정 [CASES 명세, SCRUM-50 #18 · SCRUM-105 #57] |
| 409 | `DATASET_NOT_LOADED`, `DATE_OUT_OF_RANGE`, `NO_ACTIVE_RULES`, `INVALID_RULE_PARAMS` | detection 룰 스캔 — 앞의 셋은 시작 전 거절(행 안 남김), `INVALID_RULE_PARAMS`는 `rules.params` 키가 없거나 잘못됨(배치 FAILED). 켜진 룰에 구현이 없으면 이와 달리 500 | 확정 [SCRUM-99] |
| 400·409 | `DATASET_FILES_MISSING`, `RULE_SCAN_NOT_DONE`, `SCORE_NOT_DONE` | source·detection | 〔초안〕 SD 명세 "신규 제안" |
| 409 | `SOURCE_FILE_ALREADY_LOADED` | source — 같은 파일(SHA-256)을 다시 적재. 배치 재실행 `BATCH_ALREADY_DONE`과 구분 | 확정 [SCRUM-44] |
| 409 | `CASE_ALREADY_OPENED` | detection `MarkCaseOpened` — 이미 케이스가 생긴 경보. 케이스 생성 트랜잭션째 롤백 | 확정 [SCRUM-108] |
| 409 | `LOGIN_ID_TAKEN`, `ASSIGNEE_HAS_ACTIVE_CASES` (`details.active_case_count`), `ACCOUNT_CHANGE_NOT_ALLOWED` (`details.reason` = `SELF`·`SYSTEM`) | user 계정 관리 — 아이디 중복(DB unique 위반도 같은 409) · 진행 중 담당이 남은 조사역을 승인자로 · 관리자 본인·SYSTEM 계정 변경 | 확정 [SCRUM-135 ②, 11/10 진희] |
| 502 | AI가 준 코드 그대로 (`LLM_UNAVAILABLE`, `LLM_INVALID_OUTPUT`, `LLM_BUDGET_EXCEEDED`, AI 503 `MODEL_UNAVAILABLE`·`DB_UNAVAILABLE`), `AI_UNAVAILABLE`(연결 거부), `AI_TIMEOUT`, `AI_BAD_RESPONSE`, `AI_DRAFT_NOT_FOUND`·`AI_INVALID_REQUEST`·`AI_UNAUTHENTICATED`(AI 404·400·401 이름 바꿈, SCRUM-128), `SUMMARY_CODE_CHECK_FAILED` | AI 연동 | AI 코드는 확정 [규약 v0.2 §6.3]. 백엔드 매핑 `AI_UNAVAILABLE`·`AI_TIMEOUT`·`AI_BAD_RESPONSE`는 SCRUM-17 확정. 〔제안〕 `SUMMARY_CODE_CHECK_FAILED` — 요약 응답은 200인데 `code_checks` 중 `match=false`가 있어 저장하지 않음(`details.failed_checks`), 기존 요약 유지 [규약 v0.4 §6.1, RefreshCaseSummary] |
| 500 | `INTERNAL_ERROR` | 공통 | 확정 |

AI 쪽 코드 메모 [규약 v0.2 §6.3]: `LLM_TIMEOUT`은 v0.2에서 없어지고 `LLM_UNAVAILABLE`로 합쳐졌어요. `VERIFY_UNAVAILABLE`은 에러가 아니라 **검증 결과의 `status` 값**(200 응답)이라 검증 결과 한 줄로 저장해요 [§5.3].

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
| 문서 | springdoc, `local` 프로파일에서만 `/swagger-ui.html`. DTO에 `@JsonProperty`를 쓰지 않아요 — 전역 SNAKE_CASE로 충분하고, swagger 스키마도 `OpenApiConfig`의 `ModelResolver`로 같은 이름이 나와요 [SCRUM-183] |
| 상세 응답 | 화면 하나에 요청 하나 — 케이스 상세는 경보·점수·요약·초안·이력을 한 번에, 거래 목록만 따로 페이징 |

**목록 응답 모양** — `{ "items": [...], "page": 0, "size": 20, "total": 143 }`. 목록 API는 `PageResponse.from(page)`로 반환해요. 전체 페이지 수는 프론트가 `ceil(total / size)`로 계산해요. [레퍼런스 §12] 〔코드를 이 모양으로 맞춤 — SCRUM-39〕

**시각 표기** 〔제안〕 — 감사 시각은 UTC `Z`를 붙이고, 원천 거래 시각은 시간대 없이. `01_code_rules.md` §5.

## 4. 현재 사용자 [가이드 §6.3, 레퍼런스 §9.4, 코드 `CurrentUser`]

- 유스케이스는 `actorId`를 인자로 받지 않고 `CurrentUser` 빈에서 꺼내요. `require()`는 로그인 사용자, `idOrSystem()`은 배치용.
- 요청 body에 `user_id`·`created_by` 같은 필드를 받는 API는 하나도 없어요. ADMIN이 남에게 배정할 때의 `assignee`는 **작업자가 아니라 업무 입력**이라 예외예요. [CASES 명세 §4]

## 5. `AiClient` — AI 서버 호출 [가이드 §5, 레퍼런스 §8.4·§8.5]

유스케이스는 `AiClient` 인터페이스만 봐요. 구현(`WebClientAiClient`)과 `ai_call_logs` 기록은 진희가 만들어요(SCRUM-17·18).

| 메서드 | AI 엔드포인트 | 부르는 쪽 |
|---|---|---|
| `scores(batchRunId, alerts)` | `POST /v1/scores` — 500개씩 나눠 보냄 | detection `RequestScores` |
| `createDraft(request)` | `POST /v1/str/drafts` | str `RequestStrDraft` |
| `verify(draftId, request)` | `POST /v1/str/verify` — `draftId` 는 인자로 따로(요청 본문엔 안 넣음 — AI StrictModel, SCRUM-151) | str `VerifyStrDraft` |
| `caseSummary(request)` | `POST /v1/cases/summary` | cases `RefreshCaseSummary` |
| `health()` | `GET /v1/health` — 기동 시 1회, 실패해도 기동은 함. 응답 `status·db·llm·server_version·model_version·feature_version·retrieval_version` 을 info 한 줄로 찍고, `status`·`db`·`llm` 중 하나라도 `ok` 가 아니면(null 포함) warn 한 줄 더 (SCRUM-22) | `AiHealthCheck` |

**공통 동작**
- 헤더 `X-Internal-Key`(32자 이상 난수, 환경변수), `X-Request-Id`(UUID, `ai_call_logs.request_id`와 같은 값), `X-Budget-Ms`(그 시도를 보낼 때 남은 시간 한도, ms 정수 — 재시도면 줄어든 값. health 포함 모든 시도. AI가 읽어 재생성 판단에 쓰는 건 AI 쪽 작업) [SCRUM-128].
- **시간 한도** — 초안·검증·요약처럼 **화면에서 부르는 요청은 전체 90초**(재시도 포함, 호출마다 90초가 아님). 점수 배치는 **500건 묶음 호출 하나마다** 같은 규칙이에요(배치 전체가 90초가 아님). MVP는 **동기**(`@Async`·큐·폴링은 고도화). [10/29]
- 4xx는 재시도 없이 `AiClientException` → 502. 단 409 `GENERATION_IN_PROGRESS`는 409 `DRAFT_IN_PROGRESS`로. [10/29]
- **재시도** — 최대 **1회**, 그리고 **위 시간 한도 안에 남은 시간이 있을 때만**. [10/29]
  - 다시 보내는 것(판별은 HTTP 5xx가 아니라 코드/원인): AI가 **바로 돌려준** `LLM_UNAVAILABLE`·`MODEL_UNAVAILABLE`·**503 `DB_UNAVAILABLE`**, 또는 백엔드가 AI에 **연결을 거부당한** 경우(`ConnectException`)만.
  - 다시 보내지 않는 것: AI 500 `INTERNAL` 등 **그 밖의 5xx·4xx**(코드 그대로 502), **연결 타임아웃(`ConnectTimeoutException`)**·**백엔드 쪽 응답 대기 타임아웃**(시간을 이미 다 썼고, AI가 아직 처리 중일 수 있음), `LLM_INVALID_OUTPUT`·`LLM_BUDGET_EXCEEDED`·`GENERATION_IN_PROGRESS`.
  - 재시도 후에도 실패하면 **AI가 준 코드 그대로 502**. 연결 거부로 끝나 AI 코드가 없을 때만 **`AI_UNAVAILABLE` 502**, 타임아웃은 `AI_TIMEOUT` 502. [SCRUM-17, 10/29]
  - 대상 코드는 규약 v0.2 §6.2 그대로이고, "남은 시간 안에서만"과 "백엔드 타임아웃은 제외"를 10/29에 더했어요. 규약 문구도 다음 규약 변경 때 맞출게요. 〔레퍼런스 §8.5·SD 명세의 "5xx·타임아웃 1회"는 그 전 표기〕
- AI 쪽 중첩 재시도 금지. [안건 21]
- 모든 호출은 `ai_call_logs`에 **시도마다 한 줄**(재시도하면 같은 `request_id`로 `attempt` 1·2) — **메타데이터만**: 엔드포인트, `request_id`, 상태 코드(AI가 준 HTTP 상태, 타임아웃·연결 실패는 null), 에러 코드, 소요 ms, 재시도 회차, 버전, `generation_id`, 대상(`target_type`·`target_id`). **요청·응답 본문은 실패해도 저장하지 않아요.** 실패 요청에 조사 메모·보고서 본문이 들어갈 수 있어서예요. 전문은 **AI가 그 요청을 저장했을 때만** AI 쪽 `ai.generations`에서 `request_id`로 찾을 수 있어요(연결 실패·인증 거부처럼 AI가 기록 전에 끝난 요청은 전문이 없어요). [규약 v0.2 §6.4, 10/29] 〔레퍼런스 §3.4의 "실패한 호출만 본문 저장"은 그 전 안〕
- `ai_call_logs`의 대상·버전은 엔드포인트별로 고정이에요. 버전·`generation_id`는 **2xx 응답 본문이 있을 때만** 채워요(4xx·5xx·타임아웃·연결 실패는 null). [SCRUM-18]
- **2xx인데 `AiClient`의 응답 확인(필수 칸·칸 타입·점수 묶음 `batch_run_id`·`alert_id` 대조)이 실패하면** 그 시도는 실패 행이에요 — `status_code`=실제 2xx, `error_code`=`AI_BAD_RESPONSE`, 버전·`generation_id`는 본문에서 읽은 값. 재시도 안 함(형식 오류는 재시도 대상 아님). 대상: 점수·초안·검증·요약(health 제외). [SCRUM-128]
- `AiClient`가 돌려준 **뒤** 유스케이스가 하는 업무 검사 실패(str `AiDraftResponseReader`·`SaveStrDraft`, cases `RefreshCaseSummary`의 `SUMMARY_CODE_CHECK_FAILED`)는 기록이 **성공 행 그대로**예요 — 화면엔 실패지만 AI 호출 자체는 정상이었어요. [SCRUM-128]

  | 엔드포인트 | `target_type` / `target_id` | `ai_version` (응답 필드) |
  |---|---|---|
  | `/v1/scores` | `BATCH_RUN` / `batchRunId` | `scoring_meta.model_version` |
  | `/v1/str/drafts` | `DRAFT` / `draftId` | `generation_meta.prompt_version` |
  | `/v1/str/verify` | `DRAFT` / `draftId` | `verifier_version` |
  | `/v1/cases/summary` | `CASE` / `caseNo` (요청 본문에 내부 id 가 없어 케이스 번호) | `generation_meta.prompt_version` |
  | `/v1/health` | null / null | null |

  모델 이름·`feature_version` 같은 나머지 버전은 저장하지 않아요. `generation_id`·`request_id`로 AI 쪽 기록에서 찾아요.
- 기록은 **별도 트랜잭션**(`AiCallLogWriter`, `REQUIRES_NEW`)으로, AI 응답을 받은 **호출 스레드에서** 해요(reactor 연산자 안에서 JPA 금지). 바깥 작업이 롤백돼도 실패 행은 남고, 기록이 실패해도 AI 결과·예외는 그대로예요(`WebClientAiClient`가 삼키고 경고 로그). [SCRUM-18]
- **조회** — `GET /api/v1/admin/ai-calls`, **ADMIN만**(`/api/v1/admin/**` 규칙, 권한 표 24번). `PageResponse`에 테이블 칸 그대로(snake_case, `created_at`은 UTC 초까지 `Z`)를 돌려줘요. 필터는 전부 선택이고 같이 쓰면 AND: `endpoint`·`request_id`·`error_code`(정확히 같음), `target_type`(`BATCH_RUN`·`CASE`·`DRAFT`)+`target_id`(`target_id`만 오면 400), `outcome`(`SUCCESS` = `error_code` 없음, `FAILURE` = 있음 — 타임아웃 null·2xx 빈 본문 `AI_BAD_RESPONSE` 포함), `from`·`to`(`created_at`, `from` 포함·`to` 제외, **UTC `Z`만** — `2026-11-08T00:00:00Z`·`2026-11-08T00:00:00.000Z`. 숫자(`20261108`)·오프셋(`+09:00`)·없는 날짜는 400, swagger엔 문자열로 보여요 [SCRUM-204]). 기간이 없으면 전체예요. 정렬은 `created_at`만(기본 `created_at,desc`, 같은 시각은 `id`), `page` 0부터·`size` 기본 20·최대 100. 틀린 값은 400 `INVALID_REQUEST`, 0건은 `items: []`. 이 목록을 여는 건 `access_logs`에 남기지 않아요. 코드는 `shared/ai/AiCallLogQuery`(JdbcTemplate, 읽기 전용) — `AiCallLogRepository`는 `save`만 그대로예요. [SCRUM-134]
- `AiDtos`는 규약 v0.4 모양(SCRUM-151) — 초안 요청은 `draftId·caseNo·alert(AlertPayload+createdAt)·evidenceTransactionIds·reasons·priorAlertCount·investigatorNotes·reportingInstitution` record, 검증 요청은 `generationId·revisionNo·narrative` record(`draftId` 는 인자로 따로). 응답은 백엔드가 쓰는 칸만 record 필드로 뽑고 원문은 `raw: JsonNode` 로 둠(원칙 4). 초안 응답의 `generation_meta.rewrite`(선택 칸, AI 고쳐 쓰기 기록 — AI #39, SCRUM-147)는 해석하지 않고 `raw` 그대로 `str_drafts.payload` 에 저장해요(`GenerationMetaView` 는 `ignoreUnknown`). `narrative[]` 는 `NarrativeSectionDto(section: String, text, cites, evidenceIds)` — `section` 이 String 인 이유는 enum(`str.domain.StrSection`) 을 shared 에서 참조하면 ArchitectureTest 위반이기 때문. 섹션 이름 검사는 str 도메인 몫.
- **요약 요청·응답** (규약 v0.4 §6.1, 11/6) — 요청 `CaseSummaryRequest(caseNo, alert(AlertPayload), evidenceTransactionIds, reasons, priorAlertCount, reportingInstitution)` — `reasons`·`priorAlertCount` 는 초안 요청과 같은 값·필수, `reportingInstitution` 은 선택(null). 응답 `CaseSummaryResponse(producedAt, caseNo, generationId, summaryLines, codeChecks(JsonNode), generationMeta, raw)` — `case_no` 가 없으면 502 `AI_BAD_RESPONSE`. 코드 검사가 실패해도 AI 는 200이라 저장 여부는 cases `RefreshCaseSummary` 가 정해요. 〔SCRUM-128 범위 — RefreshCaseSummary 에서 먼저 맞춤, 진희 확인 필요〕
- **점수 응답 매핑** (규약 §3, SCRUM-128) — 요청은 `ScoresRequest(batchRunId, alerts)`. AI 응답은 묶음(500개)마다 `{batch_run_id, scores[], scoring_meta, produced_at}` 이고, `WebClientAiClient` 가 묶음마다 `scoring_meta`·`produced_at` 을 그 묶음 **항목마다** 붙여 `ScoreResult` 하나로 돌려줘요 — `alertId·predictionId·score·threshold·dataQualityStatus·failureCode·reasons(JsonNode)·evidenceTransactionIds·contextTransactionIds·meta(modelVersion·featureVersion·serverVersion·latencyMs)·producedAt·raw`. `alert_scores` 한 행은 항목 하나로 채우고, `payload` 는 `payloadForStorage()`(항목 원문 + `scoring_meta` + `produced_at`). **`score_band` 는 AI가 주지 않아요** — `CasePolicy` 몫. 묶음마다 응답 `batch_run_id` 가 보낸 값과 다르거나, `scores[]` 의 `alert_id` 집합이 보낸 경보와 다르면(빠짐·남음·중복) 502 `AI_BAD_RESPONSE`. `scores()` 는 안에서 500개씩 나눠 보내고 **전부 합쳐** 돌려주므로, 묶음마다 커밋하려면 부르는 쪽이 500개 이하로 나눠 호출해요. **칸 확인**(AI 계약 `scores.py`와 같은 기준, 하나라도 틀리면 502 `AI_BAD_RESPONSE` + 실패 행, 메시지에 칸 이름만) [SCRUM-204]:
  - `scoring_meta` — `model_version`·`feature_version`·`server_version` 문자열(빈 값 허용), `latency_ms` 0 이상 정수. 없거나 타입이 틀리면 실패
  - 항목 `threshold` — 숫자, 0~1 (AI 스키마엔 범위가 없지만 `alert_scores` 제약이 0~1)
  - 항목 `score` — `data_quality_status`가 `FAILED`면 null, 그 밖엔 숫자 0~1(null도 실패)
  - `evidence_transaction_ids`·`context_transaction_ids` — 원소가 전부 1 이상 정수(0·음수·null·문자열은 실패)

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

- 전부 **관리자가 API로 직접 실행**, 스케줄러 없음. 응답은 `batch_runs` 한 행. 적재·케이스 생성은 `@Async` + 202(`batch_run_id`로 상태 조회), 룰 스캔·점수는 동기. [레퍼런스 §11, 계약서 B4 — 11/4 준혁 제안·지윤 동의]
- `batch_runs` 상태 기록은 본 작업과 **다른 트랜잭션**으로 커밋해요. 본 작업이 롤백돼도 "실패했다"는 기록이 남게. [SD 명세 §12 〔초안〕]
- 앱이 배치 도중 죽으면 `RUNNING`이 남아요. 기동 시 오래된 `RUNNING`을 `FAILED`로 정리해요. [SD 명세 §12 〔초안〕, CASES 명세 §8.3]

**재실행 규칙 (확정)** [안건 14·21]
| 배치 | 성공한 날짜를 다시 돌리면 | 실행 중이면 |
|---|---|---|
| 적재·룰 스캔 | 409 `BATCH_ALREADY_DONE` | 409 `BATCH_RUNNING` |
| 점수·케이스 생성 | **허용** — 남은 대상(미처리·실패)만 처리, 새 `BatchRun` 행 | 409 `BATCH_RUNNING` |

- 점수 재시도는 별도 유형(`SCORE_RETRY`) 없이 **점수 배치 재실행**으로 해요. [안건 14·21] 〔SD 명세 v0.1의 `SCORE_RETRY`·`/score/retry` 제안은 회의 결정 이전 안〕
- **점수 배치 재실행은 `V012`로 해결** (SCRUM-100, 11/10) — `batch_runs_succeeded_uq`에서 `SCORE_REQUEST`만 빼서 성공한 날도 다시 돌려요(남은 대상만). 같은 날짜 동시 실행은 앱이 막아요(시작 락 + `RUNNING` 확인). 적재·룰 스캔은 그대로 성공 1회.
- **배치 상태는 `SUCCEEDED`·`FAILED` 둘만**(`PARTIAL` 없음), 건수는 `rows_in`·`rows_out`만(`metrics` 칸 없음). 점수 배치의 실패·재처리 건수는 점수 응답에 바로 와요. [11/8 준혁 확정, 프론트 API 계약서 5.6]
- 〔확인 필요: 케이스 생성(`OPEN_CASES`)은 위 표에선 성공한 날도 재실행 허용인데 인덱스는 아직 성공 1회로 막혀 있어요(#94 리뷰). 적재는 원천 전체를 한 번 넣는 작업이라 **날짜와 상관없이** 성공 1회·실행 1개로 막아야 해요(적재일이 다르면 같은 키가 아님). 멈춘 `RUNNING`을 정리한 뒤 이전 작업이 늦게 끝나도 새 실행을 덮어쓰지 않는 조건도 필요해요. 테스트는 `batch_runs` 상태뿐 아니라 이전 작업이 경보·점수 같은 업무 데이터를 늦게 저장하는 경우까지(노션 미결 4번). SD 명세는 조건에 `RUNNING`도 넣자고 제안. `batch_type` 값도 회의 문서는 `SCORE`, 코드는 `SCORE_REQUEST` — 코드 값을 유지하는 방향〕

## 7. 사람이 보는 번호 (`DisplayNumber`) [레퍼런스 §8.7]

| 번호 | 형식 | 시퀀스 |
|---|---|---|
| 경보 | `ALT-{배치일 YYYYMMDD}-{6자리}` | `ops.alert_no_seq` (V005) |
| 케이스 | `CASE-{경보 기준 연도}-{6자리}` | `ops.case_no_seq` (V006) |
| 초안 | `DRF-{6자리}` (`DRF-000123`) ✅ [계약서 2.2, 11/4 채영] — 백엔드가 AI 호출 **전에** 시퀀스로 먼저 발급 [안건 23] | `ops.str_draft_id_seq` (V008, 머지됨 #28) |

- 시퀀스는 날짜별로 리셋하지 않아요. `MAX(id)+1` 방식은 금지(두 트랜잭션이 같은 번호를 받을 수 있음).
- 〔미결: 확정 보고서 번호 `STR-{연도}-{6자리}` — 서식 안건 3·5-2〕

## 8. 로깅·요청 추적 [레퍼런스 §8.6, 코드 `RequestIdFilter`·`UserIdMdcFilter`]

- `request_id`·`user_id`를 MDC에 넣어 모든 로그에 붙여요. AI 호출 땐 같은 값을 `X-Request-Id`로 보내서 AI 로그와 짝을 맞춰요.
- 로그 금지 항목은 `01_code_rules.md` §6.
- **403 거부 로그** — URL 역할·CSRF·도메인(`ForbiddenException` 하위)·MVC 안 `AccessDeniedException` 모두 WARN `access denied code={코드} method={메서드} uri={경로}` 한 줄이에요. 쿼리스트링·요청 본문·토큰·쿠키 값·예외 메시지는 안 찍어요. request_id가 붙고, 로그인 상태면 user_id도 붙어요. CSRF는 `UserIdMdcFilter`보다 앞에서 막혀서 핸들러가 `CurrentUser`로 user_id를 넣어요. 로그인 전이거나 세션이 끊긴 요청은 user_id가 없어요. [미결 30, SCRUM-74]

## 9. 열람 기록 (`access_logs`) [레퍼런스 §3.4, 계정·권한 §1, 코드 `shared/audit`·`V004_3`, SCRUM-19]

누가 언제 무엇을 봤는지 남겨요. **AOP로 자동 기록하지 않고, 조회 유스케이스가 한 줄로 직접 불러요**(레퍼런스 §3.4).

| 언제 | 누가 부르나 | 한 줄 |
|---|---|---|
| 케이스 상세 | cases `GetCaseDetail` | `accessLog.view(TargetType.CASE, caseId)` |
| 경보 상세 | detection (붙일 때) | `accessLog.view(TargetType.ALERT, alertId)` |
| STR 초안 본문 | str 초안 상세 | `accessLog.strView(draftId)` |
| 확정본 PDF | str PDF 다운로드 | `accessLog.pdfDownload(draftId)` |
| ADMIN 내보내기 | 내보내기 유스케이스 | `accessLog.export(type, id)` |
| 계정 발급 | user `CreateUser` | `accessLog.userCreated(targetUserId)` — target `USER`, 사용자는 관리자. 변경과 같은 트랜잭션 [SCRUM-135 ②] |
| 역할 변경 | user `ChangeUserRole` | `accessLog.roleChanged(targetUserId)` [SCRUM-135 ②] |
| 비활성화 | user `DeactivateUser` | `accessLog.userDeactivated(targetUserId)` — 재활성화·비밀번호 초기화는 기록 없음 [SCRUM-135 ②] |

- `AccessLogWriter`(`shared/audit`)를 주입받아 부르기만 해요. **사용자는 `CurrentUser`, IP는 지금 요청**에서 Writer가 꺼내요 — 호출부는 넘기지 않아요. action 문자열을 밖에서 넣는 메서드는 없어요.
- 기록은 **바깥 조회 트랜잭션에 참여**해요(`MANDATORY`, 같은 커넥션). 그래서 **열람 기록을 부르는 조회 유스케이스는 `@Transactional`(readOnly 아님)**이에요. 커넥션은 조회 하나에 하나, 기록과 조회는 **같이 성공·같이 실패**해요 — 기록 뒤에 조회가 실패하면 기록도 롤백되어 **본문을 실제로 돌려준 열람만** 남아요. [11/3, SCRUM-93]
- **호출 순서** — 권한 검사(담당자·역할) **통과 뒤, 본문 데이터를 읽기 전에** 한 줄. 권한에 걸려 403이면 기록하지 않아요(미결 30 결정 — 경고 로그만). 대상이 없어 404여도 기록하지 않아요.
- **상세 + 현재 초안 화면** — 케이스 상세는 `view(CASE)` 한 줄 — 같은 응답에 현재 초안 본문도 주면 `strView` 한 줄 더. STR 상세(`GET /str-drafts/{draft_no}`)는 본문 응답이든 상태·날짜만 주는 제한 응답(비담당·관리자, FR-APR-02)이든 `strView` 한 줄(미결 30 결정의 전제).
- **사용자** — 로그인 사용자가 없으면 기록하지 않고 실패해요(`CurrentUser.require()`, `user_id` 없는 열람 행 금지). 배치·SYSTEM은 열람 기록을 부르지 않아요.
- **자주 하는 실수** — ① `readOnly = true` 안에서 부르면 바로 `IllegalStateException`. ② 트랜잭션 밖(컨트롤러 등, `open-in-view: false`)에서 부르면 바로 `IllegalTransactionStateException`.
- **열람 기록 저장이 실패하면 예외를 그대로 올려서 조회도 실패해요(기록 없는 열람 금지).** 반대로 로그인·로그아웃 기록은 실패해도 응답은 그대로이고 경고 로그만 남겨요. [11/2 진희]
- 로그인·로그아웃 기록(`LOGIN_OK`·`LOGIN_FAIL`·`LOGOUT`)은 `shared/security`만 쓰는 `LoginAccessLogWriter`가 남겨요(ArchUnit으로 막음). `LOGIN_FAIL`은 401만 — 비밀번호 72바이트 초과도 401이라 한 행 남고, 400 형식 오류(빈 값·128자 초과)는 안 남겨요 [SCRUM-135 ②-b]. 팀원 코드는 부를 일이 없어요.
- 추가만 — `app_backend`는 UPDATE·DELETE 권한이 없어요. 비밀번호는 어디에도 저장·로그하지 않아요.
- action 10개와 로그인 기록 규칙은 `04_auth_user.md` §1·§3. 권한이 없어 403이 난 시도는 access_logs에 남기지 않고 앱 경고 로그 한 줄만 남겨요(미결 30, 11/2 결정) — 자세히는 §8 로그.

## 10. 마스킹 [레퍼런스 §10-2]

- API 응답의 계좌번호는 **뒤 4자리만** (`****9D80`). 응답 record에서 처리. 전체 번호는 ADMIN 내보내기에만.
- 〔확인 필요〕 `account_key`(`{bank_id}_{account_no}`)를 그대로 보내면 `account_no`를 마스킹해도 전체 번호가 보여요. 프론트 응답에서 빼거나 마스킹할지, 화면에서 계좌를 무엇으로 가리킬지 정해야 해요(프론트 API 계약서 SCRUM-21). 지금은 합성 데이터라 당장 위험은 없지만, 실데이터 기준으로 정해 둬요. → [노션 미결](https://app.notion.com/p/4077fbce53048094a838f49ca1bde5d4) 1번

## 11. DB 계정·스키마·마이그레이션 [레퍼런스 원칙 5, 코드 `db/init`·`V001`·`V002`·`application.yml`, SCRUM-124]

백엔드와 AI 서버는 **같은 PostgreSQL(`aml` DB)**을 쓰고, 스키마로 나눠요. `ops` = 백엔드, `ai` = AI 서버. DB는 백엔드 `compose.yaml`이 띄우고(프로젝트 이름 `dajaba` 고정 → 네트워크 `dajaba_default`), AI compose가 그 네트워크에 붙어요.

| 계정 | 무엇에 쓰나 | `ops` 권한 | `ai` 권한 | 누가 만드나 | `.env` 변수 |
|---|---|---|---|---|---|
| `postgres` | 슈퍼유저 — 컨테이너 첫 기동(`db/init`), 확장 만들기, `ai` 들여다보기 | 전부 | 전부 | postgres 이미지 | `POSTGRES_PASSWORD` |
| `app_migrate` | Flyway DDL만 (앱 기동 때 Flyway가 이 계정으로) | **주인** — 테이블 만들기·바꾸기 | 없음 | `01_roles.sh`(계정·`ops` 주인) | `APP_MIGRATE_PASSWORD` |
| `app_backend` | 앱(Spring) — 평소 읽기·쓰기 | USAGE + 테이블 SELECT·INSERT·UPDATE·DELETE, 시퀀스 USAGE·SELECT (기본 권한으로 자동 — `01_roles.sh`·V001). 이력 테이블은 파일마다 UPDATE·DELETE 회수(원칙 3) | **없음** (USAGE도 없음) | `01_roles.sh` | `APP_BACKEND_PASSWORD` |
| `app_ai` | AI 서버 — 실행·Alembic 둘 다 | USAGE + 원천 4개(`transactions`·`accounts`·`entities`·`customer_attributes`) SELECT만 (V002) | **주인** — 테이블 만들기·읽기·쓰기 | `01_roles.sh`(계정·`ai` 주인) | `APP_AI_PASSWORD` (AI `.env`와 같은 값) |

**마이그레이션 담당 — 서로 상대 스키마를 바꾸지 않아요**
- `ops` = **Flyway**(이 리포 `db/migration`, `app_migrate`)만. 규칙은 `05_flyway.md`.
- `ai` = **Alembic**(AI 리포 `migrations/`, `app_ai`)만. AI 서버 기동 때 `alembic upgrade head`.
- 스키마 사이 **FK 없음** — `prediction_id`·`draft_id`·`generation_id`·`request_id` 같은 값으로만 이어요. `ops` 테이블에서 `ai`를 가리키는 FK는 만들지 않아요(`05_flyway` §1).
- 계정·스키마 자체(만들기)는 `db/init/01_roles.sh`가 **볼륨이 빌 때 한 번만** 해요. 권한을 바꿀 일이 생기면 `01_roles.sh`가 아니라 다음 Flyway 번호로(이미 있는 볼륨에는 `db/init`이 다시 안 돌아서).

**pgvector — 확장 생성은 superuser만**
- db 이미지 `pgvector/pgvector:0.8.2-pg16-trixie`(버전 고정, 원래 `postgres:16`과 같은 trixie·glibc라 기존 볼륨의 collation이 그대로), 확장은 `db/init/00_extensions.sql`(`create extension if not exists vector;`)이 새 볼륨에서 `postgres`로 만들어요. `app_migrate`·`app_ai`로는 못 만들어요.
- 이미 있는 볼륨은 손으로 1회: `docker exec aml-db psql -U postgres -d aml -c "CREATE EXTENSION IF NOT EXISTS vector;"` (README "pgvector").
- AI Alembic은 확장을 만들지 않고 있는지만 확인해요(AI `shared/03`).

**DB 들여다보기 (읽기 전용)** — 컨테이너 안 접속은 비밀번호 없이 돼요
```bash
# ops — 앱과 같은 계정, 읽기 전용 세션
docker exec -it -e PGOPTIONS='-c default_transaction_read_only=on' aml-db psql -U app_backend -d aml
# ai — app_backend 로는 못 봐서 슈퍼유저, 읽기 전용 세션
docker exec -it -e PGOPTIONS='-c default_transaction_read_only=on' aml-db psql -U postgres -d aml
```
