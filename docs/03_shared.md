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
| `TooManyRequestsException` 하위 (`shared/domain`) — 기능 규칙의 횟수 제한 | **429** | 클래스 이름 UPPER_SNAKE (`QUESTION_RATE_LIMITED`). `details.retry_after_seconds` 는 던진 쪽이 줘요. 인프라 수준 제한은 여전히 범위 밖 [SCRUM-302] |
| `DomainException` 하위 전부 (위 셋 제외) | **409** | 클래스 이름 UPPER_SNAKE (`INVALID_CASE_TRANSITION`, `SELF_APPROVAL` …) |
| 낙관적 락 충돌 | 409 | `CONCURRENT_UPDATE` |
| `AiClientException` | 502 | AI가 준 코드 그대로. **예외:** AI 409 `GENERATION_IN_PROGRESS`는 `AiClient`가 409 `DRAFT_IN_PROGRESS`로 바꿔 보내요("생성 중" ≠ AI 장애) [10/29]. 백엔드 자체 코드와 이름이 같은 AI 4xx 셋은 `AI_`를 붙여 502로 — 아래 줄 [SCRUM-128] |
| AI 404 `DRAFT_NOT_FOUND` (AI가 `generation_id`를 모름) | 502 | `AI_DRAFT_NOT_FOUND` — 백엔드 404 `DRAFT_NOT_FOUND`(초안 없음)와 다른 뜻. 백엔드엔 초안이 있는데 AI가 생성본을 모름 = 데이터 어긋남 [SCRUM-128] |
| AI 400 `INVALID_REQUEST` (백엔드가 AI에 잘못 보냄) | 502 | `AI_INVALID_REQUEST` — 사용자 입력 400 `INVALID_REQUEST`와 다른 뜻(백엔드 쪽 버그) [SCRUM-128] |
| AI 401 `UNAUTHENTICATED` (`X-Internal-Key` 틀림) | 502 | `AI_UNAUTHENTICATED` — 로그인 401 `UNAUTHENTICATED`와 다른 뜻(서버 설정 문제, 다시 로그인해도 안 풀림) [SCRUM-128] |
| 권한 없음 | 403 | `FORBIDDEN` |
| 없는 경로 (`NoResourceFoundException`, 로그인한 `/api/**` 요청) | 404 | `NOT_FOUND` — "요청한 주소가 없습니다", `details` `{}`(경로를 다시 넣지 않음), 경고 한 줄(`not found method=… uri=…`) [SCRUM-157] |
| 안 되는 메서드 (`HttpRequestMethodNotSupportedException`) | 405 | `METHOD_NOT_ALLOWED` — "지원하지 않는 요청 방식입니다", `details` `{}`, `Allow` 헤더에 허용 메서드(Spring이 모르면 헤더 없음), 경고 한 줄(`method not allowed method=… uri=…`) [SCRUM-157] |
| 세션 저장소(Redis) 장애 — 연결 실패·시간 초과·쓰기 거부(maxmemory `noeviction` OOM). Redis 세션을 켰을 때만 | 503 | `SESSION_STORE_UNAVAILABLE` — "로그인 세션 저장소에 연결할 수 없습니다. 잠시 뒤 다시 시도하세요", `details` `{}`. 관리자 변경이 커밋된 뒤 세션 끊기만 실패했으면 메시지 "변경은 저장됐지만 세션 저장소에 연결할 수 없어 그 사용자의 로그인을 끊지 못했습니다", `details` `{"change_saved": true}`(04 §7). Spring Session 필터 바깥의 `SessionStoreFailureFilter`가 내요(`GlobalExceptionHandler`는 다시 던짐 — 응답을 쓰면 세션 저장을 또 시도해서). 응답을 비울 때 `X-Request-Id`·CORS 헤더(`Access-Control-*`·`Vary`)는 다시 붙여요(다른 출처 프론트가 본문을 읽게). **로그아웃(`POST /api/v1/auth/logout`)만은 비운 뒤 SESSION 만료 쿠키(`Max-Age=0`, 세션 쿠키와 같은 속성)를 붙여요** — 로그아웃 필터가 인증을 읽다 처리기 전에 끊겨 세션도 쿠키도 못 지우는데, 쿠키라도 지워야 Redis가 돌아왔을 때 그 세션으로 다시 로그인 상태가 되지 않아요(다음 요청은 401). 다른 요청은 쿠키를 그대로 둬요(Redis가 잠깐 흔들려도 모두 로그아웃되지 않게). 경고 한 줄 `session store unavailable error={오류 클래스} method=… uri=…` — 호스트·세션 ID·원인 메시지 없음 [SCRUM-273] |
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
| 404 | `NOT_FOUND` | 공통 — 없는 경로(`NotFoundException` 하위가 아님). 질문 답변이 꺼져 있을 때(`aml.ai.ask-enabled=false`) A8·C9도 이 응답 | 확정 [SCRUM-157 · SCRUM-302] |
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
| 409 | `INVALID_ASSIGNEE`, `CLOSE_REQUIRES_STR`, `POLICY_NOT_CONFIGURED` | cases — `INVALID_ASSIGNEE`는 `details.reason` = `NOT_ACTIVE_INVESTIGATOR`(비활성·조사역 아님·ADMIN이 대상 비움). `SELF_ONLY`는 배정이 ADMIN 전용이 되며 없앰(SCRUM-339). `CLOSE_REQUIRES_STR`은 초안 있는 케이스는 close-with-draft로. `POLICY_NOT_CONFIGURED`는 운영 등급 경계 없음 — LOW로 대신하지 않음 | 확정 [CASES 명세, SCRUM-50 #18 · SCRUM-105 #57] |
| 409 | `DATASET_NOT_LOADED`, `DATE_OUT_OF_RANGE`, `NO_ACTIVE_RULES`, `INVALID_RULE_PARAMS` | detection 룰 스캔 — 앞의 셋은 시작 전 거절(행 안 남김), `INVALID_RULE_PARAMS`는 `rules.params` 키가 없거나 잘못됨(배치 FAILED). 켜진 룰에 구현이 없으면 이와 달리 500 | 확정 [SCRUM-99] |
| 400·409 | `DATASET_FILES_MISSING`, `RULE_SCAN_NOT_DONE`, `SCORE_NOT_DONE` | source·detection | 〔초안〕 SD 명세 "신규 제안" |
| 409 | `SOURCE_FILE_ALREADY_LOADED` | source — 같은 파일(SHA-256)을 다시 적재. 배치 재실행 `BATCH_ALREADY_DONE`과 구분 | 확정 [SCRUM-44] |
| 409 | `CASE_ALREADY_OPENED` | detection `MarkCaseOpened` — 이미 케이스가 생긴 경보. 케이스 생성 트랜잭션째 롤백 | 확정 [SCRUM-108] |
| 429 | `QUESTION_RATE_LIMITED` (`details.retry_after_seconds`) | cases 질문하기(A8) — 한 사람이 최근 60초에 `aml.ai.ask-per-minute`(5)번 이상 물음. `case_questions` 행 수로 셈 | 확정 [계약서 3절 11/19, 설계 문서 13절 6번, SCRUM-302] |
| 409 | `LOGIN_ID_TAKEN`, `ASSIGNEE_HAS_ACTIVE_CASES` (`details.active_case_count`), `ACCOUNT_CHANGE_NOT_ALLOWED` (`details.reason` = `SELF`·`SYSTEM`) | user 계정 관리 — 아이디 중복(DB unique 위반도 같은 409) · 진행 중 담당이 남은 조사역을 승인자로 · 관리자 본인·SYSTEM 계정 변경 | 확정 [SCRUM-135 ②, 11/10 진희] |
| 502 | AI가 준 코드 그대로 (`LLM_UNAVAILABLE`, `LLM_INVALID_OUTPUT`, `LLM_BUDGET_EXCEEDED`, AI 503 `MODEL_UNAVAILABLE`·`DB_UNAVAILABLE`), `AI_UNAVAILABLE`(연결 실패 — 연결 거부·이름 조회 실패·보낸 뒤 끊김, SCRUM-229), `AI_TIMEOUT`, `AI_BAD_RESPONSE`, `AI_DRAFT_NOT_FOUND`·`AI_INVALID_REQUEST`·`AI_UNAUTHENTICATED`(AI 404·400·401 이름 바꿈, SCRUM-128), `SUMMARY_CODE_CHECK_FAILED` | AI 연동 | AI 코드는 확정 [규약 v0.2 §6.3]. 백엔드 매핑 `AI_UNAVAILABLE`·`AI_TIMEOUT`·`AI_BAD_RESPONSE`는 SCRUM-17 확정. 〔제안〕 `SUMMARY_CODE_CHECK_FAILED` — 요약 응답은 200인데 `code_checks` 중 `match=false`가 있어 저장하지 않음(`details.failed_checks`), 기존 요약 유지 [규약 v0.4 §6.1, RefreshCaseSummary] |
| 500 | `INTERNAL_ERROR` | 공통 | 확정 |
| 503 | `SESSION_STORE_UNAVAILABLE` | 공통 — Redis 세션 켰을 때 Redis 장애(§12). 프론트는 "잠시 뒤 다시"(로그인 화면으로 보내지 않음 — 세션은 살아 있을 수 있음) | 〔제안〕 [SCRUM-273] |

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
| `ask(request)` | `POST /v1/cases/ask` — 조사역 질문 답변(규약 5.4). 아래 "질문 답변" [SCRUM-291] | cases 질문 답변 유스케이스(지윤 님 이슈) |
| `health()` | `GET /v1/health` — 기동 시 1회, 실패해도 기동은 함. 응답 `status·db·llm·server_version·model_version·feature_version·retrieval_version` 을 info 한 줄로 찍고, `status`·`db`·`llm` 중 하나라도 `ok` 가 아니면(null 포함) warn 한 줄 더 (SCRUM-22) | `AiHealthCheck` |

**공통 동작**
- 헤더 `X-Internal-Key`(32자 이상 난수, 환경변수), `X-Request-Id`(HTTP 요청 안이면 그 요청의 MDC `request_id`, 배치처럼 MDC가 없으면 호출마다 새 UUID — 재시도해도 같은 값, `ai_call_logs.request_id`와 같은 값 [SCRUM-272]), `X-Budget-Ms`(그 시도를 보낼 때 남은 시간 한도, ms 정수 — 재시도면 줄어든 값. health 포함 모든 시도. AI가 읽어 재생성 판단에 쓰는 건 AI 쪽 작업) [SCRUM-128].
- **시간 한도** — 초안·검증·요약처럼 **화면에서 부르는 요청은 전체 90초**(재시도 포함, 호출마다 90초가 아님). 점수 배치는 **500건 묶음 호출 하나마다** 같은 규칙이에요(배치 전체가 90초가 아님). MVP는 **동기**(`@Async`·큐·폴링은 고도화). [10/29]
  - **질문 답변(`ask`)만 전체 30초**(재시도 포함, 규약 6.2) — `aml.ai.ask-timeout-seconds`(기본 30, 빈 값·0·음수면 기동 실패). 나머지는 `aml.ai.timeout-seconds`(90) 그대로예요. 한도는 호출마다 따로 재고, `X-Budget-Ms`도 그 한도에서 남은 값이라 질문 답변 첫 시도는 약 30000이에요. 재시도 규칙(아래)은 같아요. [SCRUM-291]
- 4xx는 재시도 없이 `AiClientException` → 502. 단 409 `GENERATION_IN_PROGRESS`는 409 `DRAFT_IN_PROGRESS`로. [10/29]
- **재시도** — 최대 **1회**, 그리고 **위 시간 한도 안에 남은 시간이 있을 때만**. [10/29]
  - 다시 보내는 것(판별은 HTTP 5xx가 아니라 코드/원인): AI가 **바로 돌려준** `LLM_UNAVAILABLE`·`MODEL_UNAVAILABLE`·**503 `DB_UNAVAILABLE`**, 또는 요청이 AI에 **닿기 전에 연결 단계에서 실패한** 경우만 — 연결 거부(`ConnectException`), 이름 조회 실패(`UnknownHostException`, compose에서 ai 컨테이너가 멈췄을 때), 경로 없음(`NoRouteToHostException`). 요청이 안 갔으니 다시 보내도 중복이 없어요. [SCRUM-229]
  - 다시 보내지 않는 것: AI 500 `INTERNAL` 등 **그 밖의 5xx·4xx**(코드 그대로 502), **연결 타임아웃(`ConnectTimeoutException`)**·**백엔드 쪽 응답 대기 타임아웃**(시간을 이미 다 썼고, AI가 아직 처리 중일 수 있음), **요청을 보낸 뒤 연결이 끊긴 경우**(`PrematureCloseException` · `SocketException`(연결 단계 셋 제외, Connection reset 등) · 리눅스 epoll `NativeIoException` → `AI_UNAVAILABLE` — AI가 이미 받아 생성 중일 수 있음) [SCRUM-229], `LLM_INVALID_OUTPUT`·`LLM_BUDGET_EXCEEDED`·`GENERATION_IN_PROGRESS`.
  - 재시도 후에도 실패하면 **AI가 준 코드 그대로 502**. AI 코드 없이 연결이 실패하면(위 연결 단계 실패, 보낸 뒤 끊김) **`AI_UNAVAILABLE` 502** "AI 서버 응답 없음. 잠시 후 다시 시도", 타임아웃은 `AI_TIMEOUT` 502. [SCRUM-17, 10/29, SCRUM-229]
  - 그 밖에 분류되지 않은 예외는 `AI_BAD_RESPONSE` 502 "AI 응답 처리 실패" — 메시지에 예외 클래스 이름을 넣지 않고, 클래스 이름은 서버 로그 `cause=` 칸에만 남겨요. [SCRUM-229]
  - 대상 코드는 규약 v0.2 §6.2 그대로이고, "남은 시간 안에서만"과 "백엔드 타임아웃은 제외"를 10/29에 더했어요. 규약 문구도 다음 규약 변경 때 맞출게요. 〔레퍼런스 §8.5·SD 명세의 "5xx·타임아웃 1회"는 그 전 표기〕
- AI 쪽 중첩 재시도 금지. [안건 21]
- **AI 주소·연결 기억** — 백엔드는 AI 서버 이름 조회 결과를 **최대 5초**만 기억하고(조회 실패는 기억 안 함), AI 전용 연결 풀에서 **4초 넘게 놀던 연결은 다시 쓰지 않아요**(AI uvicorn 기본 keep-alive 5초보다 짧게). 그래서 AI 컨테이너를 다시 만들어도 백엔드는 재시작 없이 몇 초 안에 새 주소로 붙어요. 풀의 다른 값(최대 연결 500·연결 대기 45초 등)은 라이브러리 공용 기본값 그대로. 설정은 `AiClientConfig`. [SCRUM-255]
- 모든 호출은 `ai_call_logs`에 **시도마다 한 줄**(재시도하면 같은 `request_id`로 `attempt` 1·2. 한 HTTP 요청이 AI를 두 번 부르면 두 호출도 같은 `request_id` — `endpoint`·`attempt`로 구분 [SCRUM-272]) — **메타데이터만**: 엔드포인트, `request_id`, 상태 코드(AI가 준 HTTP 상태, 타임아웃·연결 실패는 null — 단 2xx 본문을 받는 중 끊긴 경우는 실제 2xx, 아래 "2xx인데 응답 확인" 줄의 예외 [SCRUM-229]), 에러 코드, 소요 ms, 재시도 회차, 버전, `generation_id`, 대상(`target_type`·`target_id`). **요청·응답 본문은 실패해도 저장하지 않아요.** 실패 요청에 조사 메모·보고서 본문이 들어갈 수 있어서예요. 전문은 **AI가 그 요청을 저장했을 때만** AI 쪽 `ai.generations`에서 `request_id`로 찾을 수 있어요(연결 실패·인증 거부처럼 AI가 기록 전에 끝난 요청은 전문이 없어요). [규약 v0.2 §6.4, 10/29] 〔레퍼런스 §3.4의 "실패한 호출만 본문 저장"은 그 전 안〕
- `ai_call_logs`의 대상·버전은 엔드포인트별로 고정이에요. 버전·`generation_id`는 **2xx 응답 본문이 있을 때만** 채워요(4xx·5xx·타임아웃·연결 실패는 null). [SCRUM-18]
- **2xx인데 `AiClient`의 응답 확인(필수 칸·칸 타입·점수 묶음 `batch_run_id`·`alert_id` 대조)이 실패하면** 그 시도는 실패 행이에요 — `status_code`=실제 2xx, `error_code`=`AI_BAD_RESPONSE`, 버전·`generation_id`는 본문에서 읽은 값. 재시도 안 함(형식 오류는 재시도 대상 아님). 대상: 점수·초안·검증·요약·질문 답변(health 제외). [SCRUM-128, SCRUM-291]
  - 예외: 2xx 본문을 **받는 중 연결이 끊기면** 형식 오류가 아니라 연결 실패예요 — `error_code`=`AI_UNAVAILABLE`, `status_code`=실제 2xx, 버전·`generation_id`는 null(본문을 못 받음), 재시도 안 함 — 위 `ai_call_logs` 상태 코드 칸의 예외와 같은 경우. [SCRUM-229]
- `AiClient`가 돌려준 **뒤** 유스케이스가 하는 업무 검사 실패(str `AiDraftResponseReader`·`SaveStrDraft`, cases `RefreshCaseSummary`의 `SUMMARY_CODE_CHECK_FAILED`)는 기록이 **성공 행 그대로**예요 — 화면엔 실패지만 AI 호출 자체는 정상이었어요. [SCRUM-128]

  | 엔드포인트 | `target_type` / `target_id` | `ai_version` (응답 필드) |
  |---|---|---|
  | `/v1/scores` | `BATCH_RUN` / `batchRunId` | `scoring_meta.model_version` |
  | `/v1/str/drafts` | `DRAFT` / `draftId` | `generation_meta.prompt_version` |
  | `/v1/str/verify` | `DRAFT` / `draftId` | `verifier_version` |
  | `/v1/cases/summary` | `CASE` / `caseNo` (요청 본문에 내부 id 가 없어 케이스 번호) | `generation_meta.prompt_version` |
  | `/v1/cases/ask` | `CASE` / `caseNo` | `generation_meta.prompt_version`, null 이면(`OUT_OF_SCOPE` — 답 호출을 안 함) `plan_prompt_version` [SCRUM-291] |
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
- **질문 답변 요청·응답** (규약 5.4·6.1·6.2, SCRUM-291) — 요청 `AskRequest(caseNo, alert, evidenceTransactionIds, reasons, priorAlertCount, reportingInstitution, question)` = 요약 요청 6칸 + `question`. 요약 조립을 그대로 쓰려면 `AskRequest.of(summaryRequest, question)`. `question` 은 **앞뒤 공백을 자르지 않고** 그대로 보내고, 길이·공백 검사는 `AiClient` 가 아니라 유스케이스(400)가 해요. 응답 `AskResponse(producedAt, caseNo, generationId, status, kind, outOfScopeReason, answer, cites, evidenceIds, codeChecks(JsonNode), generationMeta(AskGenerationMeta), raw)` — `status`·`kind`·`outOfScopeReason` 은 enum `AskStatus`(ANSWERED·NO_DATA·UNVERIFIED·OUT_OF_SCOPE)·`AskKind`(FACT·WHY)·`OutOfScopeReason`(EDIT_REQUEST·OUTSIDE_CASE·JUDGMENT). 네 상태 모두 AI 200이에요. **응답 확인**(`requireAskResponse`, 하나라도 틀리면 502 `AI_BAD_RESPONSE` + 실패 행·재시도 없음, 메시지에 칸 이름만):
  - 필수 칸 `produced_at`·`case_no`·`generation_id`·`status`·`generation_meta`, 배열 칸 `cites`·`evidence_ids`·`code_checks`. 모르는 `status`·`kind`·사유 값은 실패
  - `case_no` = 보낸 값, `produced_at` 은 `Z` 로 끝나는 UTC 시각
  - `OUT_OF_SCOPE` 면 `kind` null·사유 값 있음, 그 밖은 `kind` 값 있음·사유 null
  - `ANSWERED` 면 `answer`(공백만 아님)·`cites` 1개 이상, 그 밖은 `answer` null·`cites` []·`evidence_ids` [] (`ANSWERED` 의 `evidence_ids` 는 [] 도 됨)
  - `code_checks` — `ANSWERED` 면 전부 `match=true`([] 도 됨), `UNVERIFIED` 면 `match=false` 1개 이상, `NO_DATA`·`OUT_OF_SCOPE` 면 []
  - `evidence_ids` — 1 이상 정수이고 **보낸 `evidence_transaction_ids` 안에 있어야** 해요(AI 스키마엔 없는 백엔드 검사)
  - `generation_meta.prompt_version` — `OUT_OF_SCOPE` 면 null, 그 밖은 문자열
  - AI 스키마(`ask.py`·`verify.py` `CodeCheck`)와 같은 칸 규칙도 봐요 — `case_no`·`generation_id` 문자열, `answer` 공백만 아님·NFC 뒤 UTF-16 400 이하(앞뒤 공백 포함), `cites`·`bundles` 원소는 문자열·공백만 아님·앞뒤 공백 뺀 값이 서로 다름(`bundles` 3개 이하, `OUT_OF_SCOPE` 면 [] · 그 밖은 1개 이상), `code_checks` 원소는 `check` 6종·`section` 서술 섹션 이름 또는 null·`draft_value`·`source_value` 칸 있음(문자열 또는 null)·`reason` 은 `match=false` 일 때만(4종), `generation_meta` 의 `llm_model`·`plan_prompt_version`·`server_version` 문자열·`tokens_in`·`tokens_out`·`latency_ms` 정수·`prompt_version` 칸 있음 [SCRUM-291 추가 검토]
  - AI 스키마와 다르게 두는 곳: 모르는 칸은 받아요(AI 는 거부 — 원칙 4). `produced_at` 은 `Z` 만 받아요(AI 는 시간대가 있으면 받아 UTC 로 바꿈)
  - `ai_call_logs.ai_version` 에 넣는 질문 답변 버전은 문자열이고 64자(칸 길이) 이하일 때만 — 아니면 null(성공·실패 행 둘 다)
  - **목업**(`aml.ai.mock=true`)은 질문 낱말로 상태를 골라요 — 위에서부터 처음 맞는 것: `수정` → OUT_OF_SCOPE·EDIT_REQUEST, `다른 케이스` → OUT_OF_SCOPE·OUTSIDE_CASE, `판단` → OUT_OF_SCOPE·JUDGMENT, `없는` → NO_DATA, `불일치` → UNVERIFIED, `왜` → ANSWERED·WHY, 그 밖 → ANSWERED·FACT. 목업 응답도 위 확인을 통과하는 모양이에요
  - **켜기 설정** `aml.ai.ask-enabled`(환경 변수 `AML_AI_ASK_ENABLED`, 기본 false) — `AmlProperties.ai().askEnabled()`. 이 칸을 읽고 꺼져 있을 때 어떻게 할지는 cases 유스케이스가 정해요 — 꺼져 있으면 상세에 `ASK_QUESTION` 없음, A8·C9는 404 `NOT_FOUND`(SCRUM-302)
  - **횟수 한도** `aml.ai.ask-per-minute`(기본 5, 1 이상 — 0 이하면 기동 실패) — `AmlProperties.ai().askPerMinute()`. 세는 쪽은 cases `AskCaseQuestion`(SCRUM-302)

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
- **케이스 생성 재실행은 `V014`로 해결** (SCRUM-238, 11/15) — 같은 인덱스에서 `OPEN_CASES`도 빼요. 대상은 SCORED·아직 케이스 없는 경보뿐이라 새로 HIGH가 된 경보만 케이스가 돼요. 동시 시작은 advisory lock(`6_006_001`) + `RUNNING` 확인.
- **배치 상태는 `SUCCEEDED`·`FAILED` 둘만**(`PARTIAL` 없음), 건수는 `rows_in`·`rows_out`만(`metrics` 칸 없음). 점수 배치의 실패·재처리 건수는 점수 응답에 바로 와요. [11/8 준혁 확정, 프론트 API 계약서 5.6]
- 〔확인 필요: 케이스 생성(`OPEN_CASES`)은 위 표에선 성공한 날도 재실행 허용인데 인덱스는 아직 성공 1회로 막혀 있어요(#94 리뷰). 적재는 원천 전체를 한 번 넣는 작업이라 **날짜와 상관없이** 성공 1회·실행 1개로 막아야 해요(적재일이 다르면 같은 키가 아님). 멈춘 `RUNNING`을 정리한 뒤 이전 작업이 늦게 끝나도 새 실행을 덮어쓰지 않는 조건도 필요해요. 테스트는 `batch_runs` 상태뿐 아니라 이전 작업이 경보·점수 같은 업무 데이터를 늦게 저장하는 경우까지(노션 미결 4번). SD 명세는 조건에 `RUNNING`도 넣자고 제안. `batch_type` 값도 회의 문서는 `SCORE`, 코드는 `SCORE_REQUEST` — 코드 값을 유지하는 방향〕

## 7. 사람이 보는 번호 (`DisplayNumber`) [레퍼런스 §8.7]

| 번호 | 형식 | 시퀀스 |
|---|---|---|
| 경보 | `ALT-{배치일 YYYYMMDD}-{6자리}` | `ops.alert_no_seq` (V005) |
| 케이스 | `CASE-{경보 기준 연도}-{6자리}` | `ops.case_no_seq` (V006) |
| 질문 | `QST-{6자리}` (`QST-000041`) — `case_questions.id` 그대로 [계약서 5.3 A8, SCRUM-302] | 없음 (identity) |
| 초안 | `DRF-{6자리}` (`DRF-000123`) ✅ [계약서 2.2, 11/4 채영] — 백엔드가 AI 호출 **전에** 시퀀스로 먼저 발급 [안건 23] | `ops.str_draft_id_seq` (V008, 머지됨 #28) |

- 시퀀스는 날짜별로 리셋하지 않아요. `MAX(id)+1` 방식은 금지(두 트랜잭션이 같은 번호를 받을 수 있음).
- 〔미결: 확정 보고서 번호 `STR-{연도}-{6자리}` — 서식 안건 3·5-2〕

## 8. 로깅·요청 추적 [레퍼런스 §8.6, 코드 `RequestIdFilter`·`UserIdMdcFilter`]

- `request_id`·`user_id`를 MDC에 넣어 모든 로그에 붙여요. AI 호출 땐 같은 값을 `X-Request-Id`로 보내서 AI 로그와 짝을 맞춰요.
- **받는 `X-Request-Id`** — 1~64자이고 `[A-Za-z0-9._-]`만이면 그대로 써요(AI와 같은 64). 헤더가 없거나 빈 값이면 새 UUID를 만들어요. 그 밖(65자 이상·줄바꿈·공백·허용 밖 문자)은 새 UUID로 바꾸고 WARN `request_id_replaced received_len={길이}` 한 줄 — 원래 값은 안 찍어요. 응답 헤더·로그·AI 호출 헤더는 모두 바꾼 뒤 값 하나예요. [SCRUM-272]
- 로그 금지 항목은 `01_code_rules.md` §6.
- **403 거부 로그** — URL 역할·CSRF·도메인(`ForbiddenException` 하위)·MVC 안 `AccessDeniedException` 모두 WARN `access denied code={코드} method={메서드} uri={경로}` 한 줄이에요. 쿼리스트링·요청 본문·토큰·쿠키 값·예외 메시지는 안 찍어요. request_id가 붙고, 로그인 상태면 user_id도 붙어요. CSRF는 `UserIdMdcFilter`보다 앞에서 막혀서 핸들러가 `CurrentUser`로 user_id를 넣어요. 로그인 전이거나 세션이 끊긴 요청은 user_id가 없어요. [미결 30, SCRUM-74]

### 로그 형식 [SCRUM-272, AI와 같은 표]

compose로 띄우면(`SPRING_PROFILES_ACTIVE: local,json`) JSON 한 줄, IDE로 `local`만 띄우면 읽기 쉬운 텍스트 그대로예요. `prod`도 `json`과 같은 형식이에요.

| 칸 | 뜻 | 예 |
|---|---|---|
| `ts` | UTC ISO-8601, 밀리초 | `2026-11-19T01:02:03.456Z` |
| `level` | `INFO`·`WARN`·`ERROR` (AI는 `WARNING` — 언어 기본값 그대로) | |
| `service` | `backend` 고정 (AI는 `ai`) | |
| `logger` | 로거 이름 | `com.team.aml.shared.ai.WebClientAiClient` |
| `msg` | 사건 이름(짧은 영문) 또는 `key=value` 한 줄 | `request_id_replaced received_len=80` |
| `request_id` | `X-Request-Id` (위 규칙) | |
| `user_id` | 로그인 뒤에만 | |
| 선택 칸 | `target`(화면 번호 `CASE-`·`DRF-`·`ALT-`) · `error_code` · `latency_ms` · `*_count` | |

- **남기지 않는 것** — 계좌번호(원본·가린 값 모두) · 금액 · STR 본문·요약 본문 · 메모 · 비밀번호 · 세션 ID · 내부 키 · 쿼리스트링·요청 본문. 테스트(`SensitiveLogDbTest`)가 상세 조회·초안 저장·수정·검증·AI 실패·DB 제약 위반 경로에서 확인해요.
- **DB 오류는 클래스·SQLState·제약 이름만** — 500 처리기에 온 `DataAccessException` 계열(`DataIntegrityViolationException` 포함)은 메시지·스택 대신 ERROR `unhandled exception error_type={클래스} sql_state={SQLState} constraint={제약 이름}` 한 줄(없는 칸은 빼요). Postgres 메시지의 `Detail: Key (…)=(값)`에 계좌가 들어갈 수 있어서예요. 다른 예외는 지금처럼 스택, 응답은 그대로 `INTERNAL_ERROR`. [SCRUM-272]
- **팀원은 자기 기능 로그만 이 형식에 맞춰요** — 새 칸이 필요하면 선택 칸 이름 규칙(`*_count`·`*_ms`)으로. MDC 키는 `request_id`·`user_id`·`target`만 JSON에 나가요.
- Loki 조회(Grafana Explore): `{compose_service=~"app|ai"} | json | request_id="<값>"` — 한 요청의 백엔드·AI 줄이 같이 나와요.

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
- **조회** — `GET /api/v1/admin/access-logs`, **ADMIN만**(`/api/v1/admin/**` 규칙, 권한 표 24번). `PageResponse`에 한 줄 `id`·`login_id`·`display_name`(행위자, `LOGIN_FAIL`은 둘 다 `null`)·`login_id_attempted`(`LOGIN_FAIL`만)·`action`·`target_type`·`target_id`·`created_at`(UTC 초까지 `Z`). **`target_id`는 화면 번호** — `CASE-2022-000057`·`DRF-000123`·`ALT-20220902-000142`·사용자는 `login_id`, 로그인·로그아웃은 `null`. **`ip`·`user_id`·DB 숫자 id는 응답에 없어요**(`ip`는 SELECT도 안 해요). 필터는 전부 선택·AND: `login_id`(행위자 — 입력한 아이디로 `LOGIN_FAIL`을 찾을 땐 `action=LOGIN_FAIL`), `action`, `target_type`+`target_id`(화면 번호, 종류에 맞는 형식이 아니면 400 `details.target_id`, `target_id`만 오면 400), `from`·`to`(AI 호출 기록과 같은 `UtcZ` 검사 — `from` 포함·`to` 제외, UTC `Z`만). 형식은 맞는데 없는 번호·아이디는 200 `items: []`. 정렬 `created_at,(asc|desc)`(기본 desc, 같은 시각은 `id`), `size` 최대 100. 이 목록을 여는 건 `access_logs`에 남기지 않아요. 코드는 `shared/audit/AccessLogQuery`(JdbcTemplate, 읽기 전용) — 번호는 SQL 한 번에 `users`·`cases`·`alerts` 테이블만 읽어 바꾸고(기능 클래스엔 안 기댐), 초안은 `DisplayNumber.draftNo`. `AccessLogRepository`는 `save`만 그대로예요. `created_at` 하나짜리 인덱스가 없어 필터 없이 부르면 전체 정렬 — MVP 행 수가 적어 이번엔 안 더해요. [SCRUM-168]

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

## 12. 운영 도구 (ops 프로필) [운영·배포 구성안 3.1~3.4, 코드 `compose.yaml`·`ops/`, SCRUM-263]

Prometheus·Grafana·Loki·Alloy·Redis·RabbitMQ·MinIO·Elasticsearch·Langfuse를 `compose.yaml`에 `profiles: [ops]`로 띄워요. 띄우는 법·도구별 표·`.env` 변수는 README "운영 도구"에 있어요. 규칙은 `ComposeStructureTest`가 CI에서 막아요: 도구는 전부 ops 프로필 · 호스트 포트 없음 · 이미지 태그(또는 digest) 고정 · healthcheck(Loki만 예외, distroless) · 메모리 상한 · app `depends_on`은 db뿐 · `${VAR:?}` 안 씀.

- **Redis 세션** — `AML_SESSION_REDIS_ENABLED=true`일 때만(기본 꺼짐 = 메모리 세션, Redis에 연결 안 함). Spring Session indexed, db 0, 키 `aml:session:*`, 만료는 `server.servlet.session.timeout`(30m). 키 공간 알림은 안 써요(`RedisSessionConfig` — 구독을 띄우지 않아 compose redis 명령은 그대로). Redis가 죽어도 앱은 뜨고 세션이 필요한 요청만 503 `SESSION_STORE_UNAVAILABLE`(§2), health에는 안 넣어요(`08_decisions.md` 미결 33) [SCRUM-273]
- **원본은 PostgreSQL 하나.** Redis는 세션만(db 0), RabbitMQ는 작업 ID만, MinIO는 PDF 파일만, Elasticsearch는 검색 색인만. 예외는 Langfuse 내부 저장(Redis db 1·MinIO `langfuse` 버킷·db의 `langfuse` DB)뿐.
- **도구가 죽어도 그 기능만 멈춰요.** 붙일 때도 지표·추적 연결은 실패를 무시(fail-open)하고, app·ai `depends_on`에 도구를 넣지 않아요.
- 로그·지표·추적에 계좌번호·금액·본문·프롬프트 전문을 넣지 않아요(NFR-COM-04, §8). Alloy는 표준출력을 그대로 옮겨요.
- 자기 기능을 붙일 곳: 노션 「운영 도구 붙이기 — 팀원별 담당」 2절. 11/24에 고정해요.

## 13. 지표 (Prometheus) [NFR-COM-04, 코드 `application.yml`·`ErrorMetrics`·`ops/prometheus`·`ops/grafana`, SCRUM-271]

Prometheus가 15초마다 앱의 지표를 **가져가요**(pull). Prometheus가 꺼져 있어도 앱은 그대로 돌아요(fail-open, 따로 코드 없음).

### 끝점 — 내부에서만

| 포트 | 경로 | 누가 | 호스트에서 |
|---|---|---|---|
| **8081** (관리 포트) | `/actuator/prometheus`, `/actuator/health` | Prometheus(`app:8081`), 로그인 없이 | **안 열림** — compose `ports`에 8081을 넣지 않아요. 듣는 주소는 기본 `127.0.0.1`(bootRun·IDE는 이 기계 안에서만), compose app 컨테이너만 `MANAGEMENT_SERVER_ADDRESS: 0.0.0.0`(같은 Docker 네트워크의 Prometheus가 들어와야 해서) |
| 8080 | `/actuator/health` | 프론트 연결 확인 화면·누구나 | 열림 — 응답은 전과 같음(UP 200 `{"status":"UP"}`, 그 밖 503 `{"status":"DOWN"}` 등, `components`·`details` 없음) |
| 8080 | `/actuator/prometheus` | — | **비로그인 401 `UNAUTHENTICATED`·로그인 403 `FORBIDDEN`** (8080에는 지표 끝점이 없고 `denyAll`에 걸림) |

- 관리 포트를 나누면 actuator가 통째로 8081로 옮겨 가요. 8080 `/actuator/health`는 **관리 포트를 8081로 나누면서 프론트 연결 확인·docs/09·권한 표 25번을 지키려고 남긴 호환용** 컨트롤러(`MainPortHealthController`)예요 — `HealthEndpoint`의 상태만 돌려줘요. Boot의 health 그룹 `additional-path`는 경로가 한 단계(`/health`)만 돼서 못 썼어요.
- **8081을 compose `ports`·nginx에 넣지 마세요.** 밖으로 여는 건 nginx 하나예요. 듣는 주소는 `application.yml`의 `${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}`라 `./gradlew bootRun`이어도 공용 와이파이의 다른 기계에서 못 들어와요. compose에서 app을 띄울 땐 `MANAGEMENT_SERVER_ADDRESS: 0.0.0.0`을 같이 넣어요. 리포의 `compose.yaml`에서 빠지면 `ComposeStructureTest`가 잡아요. 리포 밖 배포 구성은 사람이 챙겨야 해요(빠지면 Prometheus Targets에서 app이 DOWN으로 보여요).
- **`MANAGEMENT_SERVER_ADDRESS`가 빈 값·공백이면 앱이 뜨지 않아요.** 환경 변수는 yml 기본값(`127.0.0.1`)을 거치지 않고 바로 덮어서, 비어 있으면 모든 주소에 열리기 때문이에요. 변수를 지우거나(기본 127.0.0.1) `127.0.0.1`·`0.0.0.0`처럼 값을 넣어요. 조용히 127.0.0.1로 바꾸지 않아요 — 잘못 넣은 설정을 사람이 보게. 이 검사(`ManagementAddressCheck`)는 `@Lazy(false)`라 `spring.main.lazy-initialization=true`여도 돌아요.
- compose의 app은 `extends`와 `network_mode`를 쓰지 않아요. 테스트가 상속·보간(`${X:-host}`)을 풀기 전 정의만 읽어서, 값이 아니라 **키가 있는지**로 막아요(`bridge`도 위반). 목록 모양 `environment`에서는 `MANAGEMENT_SERVER_ADDRESS`를 **값과 함께 한 번만** 써요(`"MANAGEMENT_SERVER_ADDRESS"`처럼 값 없이 쓰면 호스트 값으로 덮여요).
- **`.env`·`compose.override.yml`에 `MANAGEMENT_SERVER_ADDRESS`나 app 8081 포트를 넣지 않아요.** `.env`는 `application.yml`이 읽어서 bootRun 기본값(127.0.0.1)을 덮고, override는 `ComposeStructureTest`가 안 봐요(리포의 `compose.yaml`은 app `ports`의 8081·`network_mode: host`까지 테스트가 막아요).
- **관리 포트와 앱 포트 합치기(`SERVER_PORT=8081`·`MANAGEMENT_SERVER_PORT=8080` 등)는 지원 안 해요 — Boot가 기동을 거부해요.** 포트가 같으면 Boot는 관리 전용 주소(`management.server.address`)가 있는 것 자체를 거부하는데, 우리는 기본값 127.0.0.1을 늘 넣어 두기 때문이에요.
- **bootRun에서 지표를 볼 때는 `localhost` 말고 `http://127.0.0.1:8081/actuator/prometheus`**로 열어요. 맥은 `localhost`가 IPv6 `::1`로 먼저 풀려서 연결이 안 될 수 있어요.
- env·beans 같은 다른 actuator 끝점은 열지 않아요(`exposure.include: health,info,prometheus` — `info`는 전부터 있었고 8081에서도 막혀 있어요).

### 공통 지표 두 개 (진희)

| 지표 | Prometheus 이름 | 태그 | 무엇을 봄 |
|---|---|---|---|
| HTTP 응답 시간 | `http_server_requests_seconds_{count,sum,bucket}` | `method`·`uri`(**템플릿**, 예 `/api/v1/cases/{caseNo}`)·`status`·`outcome`·`exception`·`error` | 요청 수, p50·p95·p99 (Grafana `histogram_quantile`) |
| 에러 코드별 건수 | `aml_http_errors_total` | `code`·`status` **두 개만** | 어떤 에러가 몇 번 났나 |

- 응답 시간 버킷은 **고정 10개**: 50ms·100ms·250ms·500ms·1s·2s·5s·10s·30s·90s (+`+Inf`). 기본 히스토그램(버킷 70여 개)은 끄고, Prometheus 메모리 상한(256m) 때문에 줄였어요. 90s는 AI 호출 경로를 보려고 넣었어요.
- `uri`에 실제 경로는 안 들어가요: 매핑 없는 주소는 `uri="/**"`, 로그인처럼 컨트롤러 밖에서 끝나는 요청은 `uri="UNKNOWN"`, 보안에서 막힌 요청도 템플릿(`/api/v1/admin/alerts/{alertNo}`)이에요.
- 에러 카운터는 **에러 응답을 쓰는 두 곳**에서 세요: `GlobalExceptionHandler`(컨트롤러 안 예외)와 `JsonAuthHandlers`(보안 필터의 401 `UNAUTHENTICATED`·`LOGIN_FAILED`, 403 `FORBIDDEN`·`CSRF_INVALID`, 로그인 400·500). 셀 때 예외가 나도 에러 응답은 그대로 나가요(fail-open). Tomcat이 직접 거절하는 요청(HTTP 형식 오류)은 안 세져요.
- `code` 태그 값은 §2 표의 코드만이에요. 대문자·숫자·`_` 형식이 아니면 `OTHER`, AI가 준 코드(502)가 §2 표에 없으면 `AI_OTHER`로 세요 — AI가 새 코드를 보내도 태그 값이 늘지 않게요. AI 코드 목록은 `ErrorMetrics.isAiCode` 한 곳이고 CASES 요약 실패 지표(`cases.md`)도 이걸 불러요 — 새 AI 코드는 §2 표에 넣고 여기 한 군데만 고쳐요. [SCRUM-276 #157 리뷰]

### 이름·태그 규칙 (NFR-COM-04)

- 이름: **`aml.<기능>.<무엇>`** (점으로, 소문자). Prometheus에서는 `aml_<기능>_<무엇>_total`(카운터)·`_seconds`(타이머)로 바뀌어요. 예 `aml.cases.transitions`, `aml.str.draft.generation`.
- 태그 값은 **개수가 정해진 것만**: 코드·상태·결과·룰 ID·역할·배치 종류 같은 것.
- **태그에 넣지 않아요**: 계좌번호·금액·본문·사유·`CASE-…`·`DRF-…`·`ALT-…` 같은 화면 번호·DB id·`login_id`·`user_id`·`request_id`·예외 메시지·실제 경로. 값이 계속 늘어나서 Prometheus 메모리가 터지고, 개인정보가 지표로 새요.
- 태그는 지표 하나에 **3개 이하**, 값 조합은 **수십 개 이하**로요.

### 각자 자기 지표 붙이는 법

`MeterRegistry`를 생성자로 받아서 카운터·타이머를 만들어요. 의존성은 이미 들어 있어요(`micrometer-registry-prometheus`). 붙이는 곳은 `application` 유스케이스나 `infrastructure` 쪽이에요 — `domain`에는 넣지 않아요(`ArchitectureTest`).

```java
@Service
class TransitionCase {
    private final MeterRegistry meters;

    TransitionCase(MeterRegistry meters /* , 다른 의존성 */) {
        this.meters = meters;
    }

    void execute(CaseNo caseNo, CaseStatus to) {
        CaseStatus from = /* ... */;
        // 카운터 — 태그는 정해진 값(상태 이름)만. caseNo 는 넣지 않는다
        meters.counter("aml.cases.transitions", "from", from.name(), "to", to.name()).increment();
    }
}

// 타이머 — 걸린 시간. 결과(SUCCESS·FAILED)만 태그로
Timer.Sample sample = Timer.start(meters);
String result = "FAILED";
try {
    /* AI 호출 등 */
    result = "SUCCESS";
} finally {
    sample.stop(meters.timer("aml.str.draft.generation", "result", result));
}
```

| 허용 태그 예 | 금지 태그 예 |
|---|---|
| `status="ESCALATED"`, `result="SUCCESS"`, `rule="R01"`, `code="CASE_NOT_FOUND"`, `role="APPROVER"` | `case_no="CASE-2022-000057"`, `account="…"`, `amount="15000000"`, `login_id="kim"`, `request_id="…"`, `reason="고객 진술…"` |

- 테스트에서 확인: `SimpleMeterRegistry`를 넣고 `registry.get("aml.cases.transitions").tag("to","ESCALATED").counter().count()`.
- 붙인 뒤 `docker compose --profile ops up -d app prometheus grafana` → Prometheus(`localhost:9090`) 검색창에 `aml_cases_transitions_total`.

### Grafana에서 보기

- `ops/grafana/dashboards/`에 JSON을 넣으면 자동으로 등록돼요. 공통 대시보드 **「백엔드 공통」**(`backend-common.json`): 요청 수(초당)·p95 응답 시간·에러 코드별 건수.
- 자기 대시보드는 같은 폴더에 `<기능>.json`으로 따로 만들어요(공통 파일은 고치지 않기).
- 자주 쓰는 식:
  - p95: `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{job="app"}[5m])))`
  - 에러 코드별: `sum by (code, status) (increase(aml_http_errors_total{job="app"}[5m]))`

## 14. RabbitMQ 작업 큐 (`shared/messaging`) [SCRUM-276, 운영 도구 3절 11/17 결정 3, 코드 `shared/messaging`·`application.yml`]

배치 잇기(준혁)·케이스 생성(지윤)·확정본 PDF(채영)가 **같이 쓰는 공통 설정**이에요. 브로커는 §12 ops 프로필의 `rabbitmq`(vhost `aml`). 〔ACK·재시도·DLQ 기본값은 진희 공통 정책 확정 전 초안 — 바꾸면 이 절과 `application.yml`을 같이 고쳐요〕

**지킬 것**

- **메시지에는 작업 ID만** — `{"batch_run_id": 123}`·`{"draft_id": 45}`. 날짜·대상·상태는 받는 쪽이 DB에서 읽어요. 원본은 PostgreSQL 하나.
- 메시지·로그에 계좌번호·금액·본문·프롬프트 전문 없음(NFR-COM-04). `RabbitJobPublisher`도 큐 이름·예외 종류만 남겨요.
- **기본은 꺼짐** — `AML_RABBITMQ_ENABLED=false`면 앱이 브로커에 연결하지 않아요(리스너 빈도 안 생김). 켜도 브로커가 죽으면 `JobPublisher.publish()`가 `false` → **부르는 쪽이 기존 동기 경로로** 돌려요(fail-open). app `depends_on`에 rabbitmq를 넣지 않아요. 앱 health에도 RabbitMQ를 넣지 않아요(`management.health.rabbit.enabled: false`). 계정이 틀려도 앱 기동은 막지 않아요(리스너 인증 실패를 치명 오류로 안 봄, `RabbitAuthFailOpenDbTest`).
- **받는 쪽은 같은 메시지가 두 번 와도 안전해야 해요.** 처리 중 앱이 죽으면 브로커가 다시 보내요. 예: 케이스 생성은 행이 `RUNNING`이 아니면 건너뛰고, `alert_id` UNIQUE라 다시 돌아도 중복 케이스가 없어요.
- **큐 경로와 동기 경로가 같은 작업을 동시에 돌지 않게 해요.** 확인(confirm)이 늦으면 보낸 쪽은 `false`(동기 경로로 실행)인데 브로커는 실제로 받았을 수 있어요. 결과 중복은 UNIQUE로 막혀도 `batch_runs` 건수는 나중에 끝난 쪽 값으로 덮여요. 그래서 **두 경로 모두 같은 유스케이스를 거치고, 실행 전에 작업 ID별 advisory lock을 기다리지 않고(`pg_try_advisory_xact_lock`) 잡아요.** 못 잡으면 건너뛰고(ACK), 잡은 *뒤에* 상태를 읽어요. 예: `RunOpenCasesOnce` — 리스너와 `RequestOpenCases`의 실행기 둘 다 이걸 불러요. 앱이 죽으면 연결과 함께 잠금도 풀려 재전달 때 다시 잡혀요.

**구조**

| 이름 | 무엇 |
|---|---|
| `aml.jobs` (direct 교환기) | 작업 메시지를 받는 곳. 라우팅 키 = 큐 이름 |
| `aml.jobs.dlx` (direct 교환기) | 재시도를 다 쓴 메시지 → `<큐>.dlq` |
| `JobQueues` | 큐 이름 한곳. `ALL`에 있는 큐마다 앱이 큐·`<큐>.dlq`·바인딩을 **처음 연결할 때 선언**해요(있으면 그대로) |
| `JobPublisher` | 보내기. 브로커 확인까지 기다려 `true`/`false`, 예외 안 던짐 |
| `JobGiveUpHandler` | (선택) 큐마다 하나. 재시도를 다 써서 DLQ로 가기 직전에 불림 — 작업 행 `FAILED` 등 |
| `BatchRunMessage` | `{"batch_run_id": …}` — 배치 단계가 같이 씀. 다른 ID는 자기 기능에 같은 모양 record |

| 큐 | 메시지 | 보내는 곳 → 받는 곳 | 담당 |
|---|---|---|---|
| `case.open-cases` | `BatchRunMessage`(OPEN_CASES `batch_run_id`, `RUNNING` 커밋 뒤) | `RequestOpenCases` → `OpenCasesMessageListener` → `RunOpenCasesOnce` | 지윤 |
| `str.pdf.generate` | `StrReportPdfMessage`(`draft_id`, PDF `PENDING` 커밋 뒤) | `StrReportPdfTrigger` → `StrReportPdfMessageListener` → `GenerateStrReportPdf` | 채영 |

**ACK·재시도·DLQ 기본값** (`spring.rabbitmq.listener.simple`)

- 처리 메서드가 **정상 반환 = ACK**. 예외면 같은 소비자 안에서 **1s → 2s 간격, 총 3번** 시도 → 그래도 실패면 재큐 없이 거절 → `<큐>.dlq`.
- 메시지 형식이 틀리면(JSON 변환 실패) 재시도 없이 바로 DLQ.
- `prefetch 1`·소비자 1개 — 배치처럼 무거운 작업을 한 번에 하나씩. 바꿀 큐는 `@RabbitListener(concurrency = …)`로 따로.
- 그러니 **다시 해도 소용없는 경우(없는 id·이미 끝난 작업)는 예외 대신 로그 남기고 정상 반환**해서 ACK로 버려요. 예외는 "잠깐 뒤 다시 하면 될 수 있는" 실패(DB 연결 등)에만.
- **DLQ로 보내기 직전에 그 큐의 `JobGiveUpHandler`를 한 번 불러요**(`JobMessageRecoverer`). 작업 행이 `RUNNING`으로 남으면 같은 날짜 재요청이 409 `BATCH_RUNNING`으로 막히니, 여기서 행을 `FAILED`로 바꿔요(다른 곳에서 실행 중이면 잠금을 못 잡아 안 건드림). 배치 단계 큐는 같은 모양으로 붙이면 돼요.
- DLQ에 쌓인 메시지는 자동으로 다시 돌지 않아요. 관리 UI에서 보고, 원인을 고친 뒤 API로 다시 요청해요.
- **복구 절차** — 재시도가 DB 장애 때문이었다면 포기 처리(`FAILED` 기록)도 실패할 수 있어요(로그 `작업 포기 처리 실패`). 그때는 DB가 돌아온 뒤 DLQ 메시지의 `batch_run_id` 행이 아직 `RUNNING`인지 보고, 맞으면 `FAILED`로 바꾼 다음 같은 날짜로 다시 요청해요. 형식이 틀린 메시지는 ID를 못 읽어 포기 처리를 못 하니 같은 방법으로요.
  ```sql
  update ops.batch_runs set status = 'FAILED', finished_at = now(), error_message = '큐 DLQ — 손으로 정리'
   where id = :batch_run_id and status = 'RUNNING';
  ```

**자기 큐 붙이는 법** (케이스 생성 `case.open-cases`가 예시)

1. `JobQueues`에 상수 + `ALL`에 한 줄. 이름 규칙 `batch.*`·`case.*`·`str.pdf.*` (`MessagingConfigTest`가 확인).
2. 보내는 쪽(application): 작업 행(`batch_runs` 등)을 **먼저 커밋**하고 ID만 보내요. `false`면 기존 동기 경로로.
   ```java
   if (!publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(batchRunId))) {
       executor.execute(() -> runOnce.execute(batchRunId));   // 꺼짐·브로커 죽음 → 지금처럼. 리스너와 같은 유스케이스(잠금)
   }
   ```
3. 받는 쪽: **자기 기능 `api` 폴더**에 리스너(바깥에서 들어오는 입구라 컨트롤러와 같은 계층 — `ArchitectureTest` 계층 규칙). `@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")`를 꼭 붙여요. 리스너는 ID만 넘기고, 상태 확인·실행은 application 유스케이스에서.
   리스너가 `JobGiveUpHandler`도 구현하면 재시도를 다 썼을 때 작업 행을 `FAILED`로 바꿀 수 있어요.
   ```java
   @Component
   @ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
   public class OpenCasesMessageListener implements JobGiveUpHandler<BatchRunMessage> {
       @RabbitListener(queues = JobQueues.CASE_OPEN_CASES)
       public void onMessage(BatchRunMessage message) { runOnce.execute(message.batchRunId()); }

       public String queue() { return JobQueues.CASE_OPEN_CASES; }
       public Class<BatchRunMessage> messageType() { return BatchRunMessage.class; }
       public void onGiveUp(BatchRunMessage message) { runOnce.giveUp(message.batchRunId()); }
   }
   ```
4. 테스트: 브로커 없는 단위 테스트(`RunOpenCasesOnceTest`처럼 건너뛰기·잠금·포기 조건) + 진짜 브로커 통합 테스트(`OpenCasesQueueDbTest` — compose와 같은 `rabbitmq:4.4.0-management-alpine`를 `GenericContainer`로, vhost `aml`, `aml.messaging.enabled=true`). `RabbitMQContainer`는 계정을 guest로 덮어써서 안 써요. `test` 프로필은 기본 꺼짐이라 다른 테스트에는 영향이 없어요.

**띄우기·확인** — README "RabbitMQ 켜고 띄우기". IDE에서 띄운 앱은 `compose.override.yml`로 `127.0.0.1:5672`를 열고 `.env`에 `AML_RABBITMQ_ENABLED=true`(호스트 기본 `localhost`).
