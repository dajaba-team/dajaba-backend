# 01. 코드 규칙

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

코드 짜기 전과 PR 올리기 전에 보는 규칙이에요. 〔제안〕 표시만 빼면 전부 확정된 규칙이고, 출처를 달았어요.

## 1. 규칙 세 줄 — 이것만은 꼭 [README]

1. **DB 구조는 Flyway 파일만.** `V{번호}__{설명}.sql`, PR 하나에 파일 하나, 번호는 팀 채팅에 먼저 예약. 이미 적용된 파일은 고치지 않아요. → `05_flyway.md`
2. **업무 규칙은 엔티티 안에.** `setStatus()` 없음. 상태는 `case.escalate()` 같은 메서드로만 바꿔요. `@Transactional`은 application에만.
3. **기능 폴더끼리는 application 유스케이스로만 부른다.** 의존 방향 `source ← detection ← cases ← str`. `ArchitectureTest`가 CI에서 막아요. → `02_structure.md`

## 2. 테이블·엔티티를 만들 때 다섯 가지 약속 [가이드 §2, 레퍼런스 §2]

나중에 바꾸려면 모든 테이블을 고쳐야 하는 것들이라 처음에 정했어요. 이 다섯 개만 지키면 나머지는 리뷰에서 고칠 수 있어요.

| # | 약속 | 내용 |
|---|---|---|
| 1 | 키는 숫자, 사람이 보는 번호는 따로 | PK는 `id bigint generated always as identity`. 화면 번호(`CASE-2022-000057`, `ALT-20220903-000142`)는 별도 컬럼(`case_no`, `alert_no`)에 unique. FK는 항상 숫자 `id`. 예외: `accounts`·`entities`는 IBM 문자 ID가 PK |
| 2 | 사람이 고치는 테이블엔 감사 컬럼 4개 | `created_at`, `created_by`, `updated_at`, `updated_by` — `BaseEntity` 상속으로 자동. 원천·로그 테이블은 `created_at`만 → `CreatedOnlyEntity` |
| 3 | 이력 테이블은 추가만 | `case_history`, `str_revisions`, `approvals`, `access_logs`, `ai_call_logs`는 INSERT만. UPDATE·DELETE 메서드 자체가 없고 DB 권한도 뺌(`revoke update, delete`). "수정"은 새 행 |
| 4 | 조회에 쓰는 값은 컬럼, AI 응답 원문은 `jsonb` | WHERE·ORDER BY·JOIN에 쓰면 컬럼, 보여주기만 하면 `payload jsonb` 안에. jsonb 안 값으로 정렬하는 쿼리 금지 — 필요하면 컬럼으로 뽑기 |
| 5 | 스키마는 `ops`와 `ai` 둘, 안 넘어간다 | 백엔드 테이블은 전부 `ops`. `ai` 스키마를 가리키는 FK 금지(`prediction_id`는 값만 저장) |

**원천 테이블 규칙** [가이드 §1, 레퍼런스 §3.1·§10]
- `transactions`·`accounts`·`entities`·`customer_attributes`는 적재 후 **UPDATE·DELETE 없음**. 컬럼 추가도 안 돼요(AI도 이 테이블을 읽어요).
- `transactions.is_laundering`(정답 라벨)과 `pattern_type`·`pattern_id`는 **엔티티에 매핑하지 않고, 어떤 응답에도 내보내지 않아요.** [레퍼런스 §10-10, SD 명세 §2.2]
- 원천 엔티티는 `@Immutable`, setter 없음, `transaction_id`는 `@GeneratedValue` 없이 `@Id`만. [레퍼런스 §8.1] 〔확인 필요: 안건 1 이후 `transaction_id`가 DB 자동 발급으로 바뀌면 매핑도 바뀜 — `06_features/source_detection.md`〕

## 3. 이름 [레퍼런스 §4.4, 컨벤션]

| 종류 | 규칙 | 예 |
|---|---|---|
| 엔티티 | 업무 용어 그대로, 단수 | `Case`, `StrDraft`, `Alert` |
| 유스케이스 | 동사 + 목적어, 한 클래스 한 메서드 `execute` | `EscalateCase`, `ApproveStr` |
| 리포지토리 | 인터페이스 `{엔티티}Repository`(domain), 구현 `{엔티티}JpaRepository`(infrastructure) | `CaseRepository` / `CaseJpaRepository` |
| 요청·응답 | 유스케이스 + `Request`/`Response`, Java `record` | `EscalateCaseRequest` |
| 상태 | enum, 규약의 UPPER_SNAKE 그대로 | `CaseStatus.PENDING_APPROVAL` |
| 도메인 예외 | `…Exception`, 에러 코드는 클래스 이름을 UPPER_SNAKE로 | `InvalidCaseTransitionException` → `INVALID_CASE_TRANSITION` [코드 `DomainException`] |
| 테이블·컬럼 | snake_case, 테이블은 복수 | `str_drafts`, `current_draft_id` |
| 클래스 / 메서드·변수 / 상수 / 패키지 | PascalCase / camelCase / UPPER_SNAKE_CASE / 소문자 | [컨벤션] |

사람이 보는 번호 생성은 `shared/domain/DisplayNumber` 한 곳에서만. [레퍼런스 §4.4·§8.7]

## 4. 계층과 트랜잭션 [가이드 §3·§5, 레퍼런스 §4.2·§8.4]

- 요청은 **api → application → domain → infrastructure** 한 방향.
- 어디 둘지 판단하는 한 줄: **if 문에 업무 용어가 들어가면 domain, 여러 리포지토리를 묶어 부르면 application, 바깥과 통신하면 infrastructure, HTTP면 api.**
- `@Transactional`은 **application 유스케이스 클래스에만**. 컨트롤러·리포지토리·엔티티에는 없어요. 읽기는 `@Transactional(readOnly = true)`.
- **AI 호출은 트랜잭션 안에서 하지 않아요.** 최대 90초 동안 DB 커넥션을 잡게 돼요. "DB 작업 → HTTP 호출 → DB 작업" 세 단계로 나눠요(예: `RequestStrDraft`(트랜잭션 없음) → `SaveStrDraft`(트랜잭션)).
- 컨트롤러에 try-catch 없음. 예외는 `GlobalExceptionHandler`가 바꿔요.
- 컨트롤러는 `@Valid`만 보고 바로 유스케이스를 불러요. "이 상태에서 가능한가"는 엔티티가 검사해요.
- 조회도 유스케이스를 거쳐요(열람 기록이 거기서 남아요). 컨트롤러가 리포지토리를 직접 부르지 않아요.
- 엔티티를 그대로 응답으로 내보내지 않아요. 응답 `record`로 바꾸고, 계좌번호 마스킹도 거기서.

## 5. JSON [레퍼런스 §8.3·§12, 규약 §0]

- `snake_case`는 `application.yml`의 Jackson 설정이 해요. **DTO에 `@JsonProperty`를 쓰지 않아요.**
- 값이 없으면 필드를 빼지 않고 `null`, 빈 목록은 `[]`(응답 record 컴팩트 생성자에서 보정).
- 시각은 ISO 8601 문자열. IBM 거래 시각(`ts`)은 **시간대 없는** `LocalDateTime`, DB `timestamptz` 감사 시각은 `Instant` — 둘을 섞지 않아요. 〔확인 필요: 감사 시각을 응답에서 `Z`를 붙여 보낼지 — CASES 명세 §4 지적〕
  - 〔제안〕 감사 시각(`created_at` 등)은 UTC로 **`Z`를 붙여** 보내고(`2026-10-29T01:52:00Z`), 원천 거래 시각(`ts`)은 지금처럼 시간대 없이(`2022-09-01T00:20:00`). 프론트가 `Z` 유무로 둘을 구분할 수 있어요.
- 금액은 `BigDecimal`(**`double` 금지**), JSON에선 문자열이 아닌 숫자 + `currency` 필드 쌍. 서로 다른 통화는 더하지 않아요(환산 없음). [ML 규약 §0]
- 알 수 없는 필드가 와도 깨지지 않게 `fail-on-unknown-properties: false`.
- 목록 응답은 `PageResponse.from(page)` → `{items, page, size, total}` 하나. [레퍼런스 §12, SCRUM-39] → `03_shared.md` §3

## 6. 로그 [레퍼런스 §8.6·§10-3]

- 형식: JSON 한 줄. `request_id`·`user_id`는 필터가 MDC에 넣어 자동으로 붙어요.
- **로그에 쓰지 않는 것:** 거래 금액, 계좌번호, 고객명, `narrative` 본문, 비밀번호, 세션 ID, 내부 키. **ID와 건수만.**
- 레벨: 상태 전이·AI 호출은 INFO, 예상된 도메인 예외는 WARN, 500은 ERROR. 스택 트레이스는 로그에만(응답에 없음).

## 7. 보안 [레퍼런스 §10]

| # | 규칙 |
|---|---|
| 1 | 비밀값(DB 비밀번호, `AI_INTERNAL_KEY`, `DEV_USER_PASSWORD`)은 환경변수로만. `.env`는 커밋 금지, 값은 노션 「.env」 페이지 |
| 2 | API 응답의 `account_no`는 **뒤 4자리만**(`****9D80`). 〔확인 필요〕 `account_key`에 계좌번호가 통째로 들어 있어서 프론트 응답에서 어떻게 할지 미결 — `03_shared.md` §9 |
| 3 | SQL은 파라미터 바인딩만(네이티브도 `:param`). 문자열 연결로 SQL 만들면 반려. 정렬 컬럼은 화이트리스트 |
| 4 | 모든 요청 record에 `@Valid` + `@NotNull`/`@Size`. `comment` 2,000자, `investigator_notes` 10,000자, `narrative[].text` 섹션당 5,000자 |
| 5 | 요청 body로 사용자 ID를 받지 않아요. 현재 사용자는 `CurrentUser`에서 [가이드 §6.3] |
| 6 | 정답 라벨(`is_laundering`)은 화면·응답·AI 요청 어디에도 없음 |

## 8. 하드코딩 [PR 템플릿]

비밀값은 `.env`·환경변수, 설정은 `application.yml`의 `aml.*`(`AmlProperties`). 코드에 매직 넘버·URL·키를 박지 않아요. 지금 있는 설정 키: `aml.ai.base-url`, `aml.ai.internal-key`, `aml.ai.timeout-seconds`(90), `aml.ai.max-batch`(500), `aml.dataset-dir`. [코드 `application.yml`]

## 9. 테스트 [레퍼런스 §6, PR 템플릿]

**먼저 쓰는 곳 세 군데(TDD)** — 순수 Java라 DB 없이 초 단위로 돌아요.
1. 상태 전이 — `CaseTest`, `StrDraftTest` (그림의 화살표 하나 = 테스트 하나, 금지 전이·끝 상태도 각각)
2. 승인 게이트 — `ApprovalGateTest`
3. 룰 판정 — `FanInRuleTest` 등 룰별 한 클래스 (2단계 Java 판정만. 1단계 SQL 집계는 리포지토리 테스트로)

**코드 다음에 붙이는 테스트**
| 대상 | 방법 | 범위 |
|---|---|---|
| 유스케이스 | `@SpringBootTest` + Testcontainers PostgreSQL | 성공 1 + 대표 실패 1. 이력 행이 실제로 들어갔는지 DB에서 확인 |
| 리포지토리 | `@DataJpaTest` + Testcontainers | 직접 쓴 쿼리만. 룰 집계 SQL은 여기 |
| AI 클라이언트 | WireMock | 정상, 재시도 1회, 타임아웃, 에러 코드, `ai_call_logs` 행 |
| API | `@WebMvcTest` | 400·403·404·409 에러 형식 |
| 권한 | `@WithMockUser(roles = "INVESTIGATOR")` | `SecurityConfig`를 고쳐서 테스트를 통과시키지 않아요 [가이드 §6] |

- PR마다 **정상 1 + 경계 1 + 실패 1**은 있어야 해요. [PR 템플릿]
- **H2 금지.** `jsonb`·`inet`·`varchar[]`·identity가 PostgreSQL과 다르게 동작해요.
- 안 하는 것: getter·setter·DTO 변환 테스트, 리포지토리를 Mockito로 모킹한 유스케이스 단위 테스트, 커버리지 목표치.
- `ApplicationContextTest`는 Docker가 있어야 돌아요. 로컬에서 Docker 없이 skip이면 PR "확인 방법"에 적어요. [PR 템플릿]

## 10. 브랜치·커밋·PR [컨벤션, PR 템플릿]

- 브랜치: `feat|fix|refactor|docs|test|chore/{Jira 이슈 키}` (예: `feat/SCRUM-19`). PR 대상은 `develop`.
- 커밋: `Gitmoji type: 작업 내용`, 명사형 끝맺음 (`✨ feat: 진짜 로그인 구현`).
- PR: 제목·본문에 Jira 키와 링크. 템플릿 항목(Jira · Key Changes · 확인 방법 · To Reviewers · 참조한 문서 · 체크리스트) 채우기.
- **한 가지 일만, 목표 ≤ 400줄**(생성 파일 제외).
- `TODO`엔 안건 번호: `// TODO(Open-11):`
- 새 라이브러리는 3줄 사유(유지보수 · 무게 · 라이선스).
- **최소 1명 Approve 후 머지.** 머지 전 `develop` 최신 반영. 머지 후 Jira 상태 갱신, 필요하면 노션 수정.
- 리뷰는 근거와 개선 방향을 담은 **제안형**으로, 요청자는 코멘트마다 반영 여부나 의견을 남겨요.
- 그림(`docs/flows`)과 코드가 같이 바뀌면 **같은 PR에서** 그림도 고쳐요. [flows README]
- 〔제안〕 새 에러 코드는 `03_shared.md` §2 표에 먼저 추가하고 코드에 넣어요.
- 〔제안〕 `shared/`·보안 설정을 바꾸는 PR은 머지할 때 #개발에 공지 네 줄을 남겨요. 형식은 `03_shared.md` 맨 위.

## 11. 자주 하는 실수 모음 [가이드 각 절 "자주 하는 실수"]

- 원천 테이블에 컬럼 추가·UPDATE하는 코드, `is_laundering` 매핑
- 새 테이블에 `BaseEntity` 상속 깜박 → 기동 시 `validate` 에러(정상이에요. 상속을 추가하면 돼요)
- 이력 테이블에 `delete()`나 setter 만들기
- 컨트롤러에서 리포지토리 바로 부르기, 유스케이스에 `if (case.getStatus() == …)` 쓰기
- 엔티티를 그대로 응답으로 내보내기
- `case.setStatus(…)` 만들고 싶어지기 → 전이 메서드를 불러요
- 유스케이스에서 `caseHistoryRepository.save(...)`를 따로 하기 → `Case`가 cascade로 같이 저장해요(두 번 들어가요)
- 그림에 없는 상태 화살표를 임의로 추가하기 → 미결 사항으로 올려요
- `@Transactional` 유스케이스 안에서 `aiClient` 부르기
- AI 실패 때 케이스 상태를 되돌리려 하기 → `ESCALATED`에 머무는 게 맞아요
- 요청 body로 `assignee_id`·`user_id`를 받아 그대로 쓰기
- `shared/`를 직접 고치기 → 진희에게 PR로 요청
