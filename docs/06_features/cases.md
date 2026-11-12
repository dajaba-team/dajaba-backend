# 06. cases — 조사

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 담당 지윤 · 정리 진희

담당자 문서는 아직 초안이라 `확정` / **〔초안〕** / **〔확인 필요〕**를 나눠 적었어요(뜻은 [`../README.md`](../README.md) "읽는 법"). 이 파일은 **담당자가 고쳐요.** 노션 초안을 바꾸면 같은 주에 이 파일도 PR로 갱신해 주세요.

## cases — 조사 (지윤)

**하는 일** — 점수 높은 경보를 사람이 조사하는 업무(케이스)로 만들고, 담당자·근거·메모·상태·이력을 관리해요. 탐지와 STR 사이를 잇는 곳이에요. **HIGH 경보 하나당 케이스 최대 하나.** [CASES 문서 §1, 레퍼런스 §3.3]

**상태 9개 + 반려 코드** [가이드 §4, 안건 11·24]

`OPEN · REVIEWING · ESCALATED · DRAFTED · PENDING_APPROVAL · APPROVED · REPORTED · REJECTED · CLOSED` (`REPORTED`·`CLOSED`는 끝). `APPROVED`는 내부 승인 완료이고, 모의 제출(API 12-2)을 거쳐 `REPORTED`가 돼요 — 엔티티는 `Case.report()`, 이력 action은 `REPORT`(V010, SCRUM-174). 모의 제출 API·권한(APPROVER)은 str 몫이에요. 반려 이유는 상태를 늘리지 않고 **별도 값** `rejection_reason_code`(`NEED_REVISION`·`NOT_REPORTABLE`)로 둬요. 〔초안: REJECTED일 때 필수, 다른 상태에선 null — CASES 명세 §2.1, 지우는 시점은 STR과 공유 필요〕

**전이 표** (확정) [가이드 §4, 레퍼런스 §5.1, 안건 11·24]
| 현재 → 결과 | 메서드 | 누가 | 조건 |
|---|---|---|---|
| (생성) → OPEN | `Case.open(alert)` | SYSTEM (케이스 생성 배치) | CasePolicy 통과 |
| OPEN → REVIEWING | `assign` | 조사역 본인 / ADMIN 지정 | 활성 조사역 |
| (상태 그대로) | `reassign` | ADMIN | REVIEWING·ESCALATED·DRAFTED·REJECTED |
| REVIEWING → ESCALATED | `escalate` | 담당 조사역 | 별도 버튼, AI 호출 없음 |
| REVIEWING → CLOSED | `close(reason, comment)` | 담당 조사역 | 사유 필수 |
| ESCALATED → DRAFTED | `attachDraft` | str → cases | 초안 저장과 같은 트랜잭션 |
| DRAFTED → DRAFTED | `attachDraft` | str → cases | 재생성, 이전 초안 SUPERSEDED |
| DRAFTED → PENDING_APPROVAL | `submitForApproval` | 담당 조사역 (str 진행) | **현재 본문**의 검증 통과 |
| PENDING_APPROVAL → APPROVED | `approve(approverId, approverWasAssignee)` | APPROVER | **지금·예전 담당 ≠ 승인자**(409 `SELF_APPROVAL`, 미결 32 · V010), 초안 LOCKED 같은 트랜잭션 |
| APPROVED → REPORTED | `report` 〔메서드는 API 12-2 구현 때 추가〕 | APPROVER | 승인 완료 건만. 제출 기록·Case REPORTED·이력 같은 트랜잭션(str 진행) |
| PENDING_APPROVAL → REJECTED | `reject(approverId, approverWasAssignee, code, comment)` | APPROVER | 코드·사유 필수. 승인과 같이 지금·예전 담당이면 409 `SELF_APPROVAL` |
| REJECTED → DRAFTED | `reopenDraft` | 담당 조사역 (str 진행) | `NEED_REVISION`만. 수정본 저장(`TouchCaseForRevision`)·새 초안 저장(`AttachDraftToCase`)과 같은 트랜잭션 |
| ESCALATED·DRAFTED → CLOSED | `close` | 담당 조사역 | 사유 필수. 초안 있으면 str 유스케이스가 SUPERSEDED와 같은 트랜잭션 |
| REJECTED → CLOSED | `close` | 담당 조사역 (str 진행) | `NOT_REPORTABLE`만. 종결 사유는 아래 세 값 중 조사역이 고름, 반려 코드는 REJECT 이력에 이미 있음 |

**금지** — OPEN → ESCALATED, PENDING_APPROVAL → CLOSED, `NEED_REVISION` 반려 건 종료, `NOT_REPORTABLE` 반려 건 수정·재생성, APPROVED에서 REPORTED 말고 다른 곳으로 이동, REPORTED·CLOSED 재개. 전부 409 `INVALID_CASE_TRANSITION`. 전이 가능 여부는 `CaseStatus` enum 한 곳, 전이 때마다 `case_history` 한 행. 반려 뒤 이동 검사는 **cases의 반려 코드 값만** 봐요(approvals는 이력). [가이드 §4, 안건 24]

**상태 말고 검사하는 것** [가이드 §4.3, 레퍼런스 §5.2]
| 언제 | 검사 | 예외 |
|---|---|---|
| 승인 요청 | 현재 본문을 검증했고 `passed = true` | `DraftNotVerifiedException` |
| 승인 | 승인자 ≠ 담당, 역할 APPROVER | `SelfApprovalException`, `NotApproverException` (403 — ForbiddenException 상속, SCRUM-67) |
| 종료·반려 | 사유가 비어 있지 않음 | `CommentRequiredException` 〔초안: CASES 명세는 사유 누락을 400으로 바꾸는 안〕 |
| 보완 반려 뒤 재요청 | 본문 해시 ≠ 반려 당시 해시 (새 `draft_id`는 제외) | `NarrativeUnchangedException` (str `ApprovalGate`) |

- `close_reason`: `FALSE_POSITIVE`, `DUPLICATE`, `NO_ACTION`. [레퍼런스 §3.3]
- **`NOT_REPORTABLE` 반려 건 종결도 `close_reason`은 위 세 값 중 하나**(보통 `NO_ACTION`)를 조사역이 골라요. 종결 사유는 "왜 닫았나", 반려 코드는 "승인자가 뭐라 했나"라 다른 질문이고, 반려 코드는 REJECT 이력 행(`reason_code = NOT_REPORTABLE`)에 이미 남아 있어요. 그래서 `close-with-draft` 요청도 `close_reason`을 받아요. 규약 4절의 "종료 사유에 반려 코드"는 이 뜻으로 읽어요. [#39 리뷰 1번, 11/3 지윤·채영]
- 변경 요청은 모두 `version` 확인(`@Version`). 요청 값 비교만 하고 커밋 때 낙관적 락을 빼먹지 않아요. [CASES 명세 §8.3]

**`CasePolicy` — 등급 계산** [안건 2]
- **확정:** HIGH/MEDIUM/LOW는 cases가 계산해요. AI 추천값과 실제 적용값을 따로 두고, 정책 버전과 모델 버전을 따로 기록해요.
- 〔초안 — CASES 명세 §7.1〕 `0 ≤ low_to_medium < medium_to_high ≤ 1`, `score ≥ medium_to_high → HIGH` … 경계값은 아직 협의 전이라 **임의 기본값을 두지 않아요.** 정책이 없으면 LOW가 아니라 명시 실패(`POLICY_NOT_CONFIGURED` 제안).

**케이스 생성 배치 (`OPEN_CASES`)** [레퍼런스 §11, 안건 14, CASES 명세 API-14 〔초안〕]
- `SCORED` · 미생성 경보(detection `FindAlertsForCaseOpening`) → CasePolicy로 분류 → **HIGH만** 케이스. 경보별로 `Case` + OPEN 이력 + `MarkCaseOpened`를 한 트랜잭션.
- **배치 전체에는 트랜잭션을 걸지 않아요.** 경보 1건 = `OpenCaseForAlert`(`REQUIRES_NEW`) 한 트랜잭션이라, 한 건이 실패해도 그 건만 롤백되고 다음 경보로 가요. `MarkCaseOpened`의 409는 트랜잭션째 롤백이라 배치를 하나로 묶으면 앞에서 만든 케이스까지 되돌아가요. [#46, 슬랙 11/5]
- 경보별 결과: HIGH 아님 → 안 만듦 · 409 `CASE_ALREADY_OPENED` 또는 `cases.alert_id` 중복(그 경보 케이스가 실제로 있을 때) → 건너뜀 · 그 밖 → 실패로 세고 계속.
- `batch_runs`(`OPEN_CASES`): 실패가 한 건이라도 있으면 **FAILED**(건수 메시지 `대상 · 생성 · HIGH 아님 · 이미 있음 · 실패`), 없으면 SUCCEEDED(`rows_in` 대상, `rows_out` 생성). FAILED인 날은 다시 돌리면 남은 대상만 처리.
- `cases.alert_id` UNIQUE로 중복 방지. 성공한 날짜의 남은 대상 **보충 실행**은 안건 21 결정이지만, 지금은 V004 `batch_runs_succeeded_uq` 때문에 409 `BATCH_ALREADY_DONE` 〔인덱스를 좁히는 마이그레이션 필요〕.
- 시작 전 거절(행 안 만듦): 정책 비어 있음 `POLICY_NOT_CONFIGURED`, 실행 중 409 `BATCH_RUNNING`, 이미 성공 409 `BATCH_ALREADY_DONE`. 케이스 번호 연도 = 경보 `reference_ts` 연도, 행위자 SYSTEM.
- 경계값 설정 `aml.case-policy.{version, low-to-medium, medium-to-high}` — 기본값 없음(평가 뒤 확정, 미결 23). 생성 당시 점수·모델·정책 버전 저장 칸은 〔다음 PR — Flyway 번호 예약 후〕. [SCRUM-105]
- **API — 구현됨 (SCRUM-105)** `POST /api/v1/admin/batches/open-cases?date=YYYY-MM-DD` → **202** `{batch_run_id, batch_type, batch_date, status: "RUNNING"}`(계약서 B4). `RequestOpenCases`가 `start()`로 RUNNING을 커밋한 뒤 `run()`을 Boot 기본 실행기(`applicationTaskExecutor`)에 넘겨요 — 적재(#56 `RequestIbmDatasetLoad`)와 같은 모양. 실행기가 거절하면 FAILED로 남기고 500. 결과는 `GET /admin/batches/{batch_run_id}`(#56). 시작 전 거절 409는 그대로 동기 응답. 〔`date` 누락·형식 오류 400은 #56의 `GlobalExceptionHandler` 쿼리 파라미터 처리가 들어와야 해요 — 지금은 500〕

**메모** [안건 16 — 일부 미결]
- 결정표 권장은 `case_history`의 `NOTE` 행(메모 전체를 매번 저장). 지윤 의견은 `case_note_history` 분리. **이름(A/B)이 엇갈려서 최종 확인 필요** — 지윤·진희.
- 〔초안 — CASES 명세 API-05·15〕 NOTE 행 방식으로 전문 저장, 같은 내용은 no-op, 빈 문자열은 명시적 비우기, `Case.version`도 같이 올림.
- **구현됨 (SCRUM-176) — NOTE 행 방식.** 지금 메모 = 마지막 NOTE 행의 `comment`(`CaseNotes` 한 곳, 상세 `investigator_notes`도 같은 값). 새 컬럼·마이그레이션 없음.
  - `UpdateCaseNotes` — A3 `PUT …/notes {investigator_notes, version}` → 200 M. 순서: 404 → version 409(내용이 같아도) → 403 `NOT_ASSIGNEE` → 409 `INVALID_CASE_TRANSITION`(`details.action = UPDATE_NOTES`) → 같은 내용(메모 없을 때 `""` 포함)이면 no-op(version 그대로). 바뀌면 `Case.updateNotes`가 NOTE 이력(상태 그대로) + `bumpVersion`. null·누락·10,001자 이상은 400.
  - `ListCaseNoteHistories` — C8 `GET …/note-histories?page&size` → `{items:[{before_text, after_text, case_version_before, created_by, created_at}], page, size, total}`, 최신 먼저(id DESC). `before_text`는 바로 앞 NOTE 행(페이지 밖이어도), 첫 기록은 null. 열람 기록은 안 남김(C7과 같음).
  - 상세 최근 이력 20건에서 NOTE는 뺌(명세 API-11 — 전문은 C8).

**AI 조사 요약** 〔초안 — CASES 명세 API-12·13·§7.2〕 "DB → AI → DB" 패턴. 입력 스냅샷과 `input_sha256`을 저장해 `is_current`를 계산(케이스 version 차이로 계산하지 않음). 실패해도 조사는 계속(요약은 보조). AI 실패를 빈 요약·LOW로 바꾸지 않아요.

**`RefreshCaseSummary` — 구현됨 (API-12, 컨트롤러 `POST …/refresh-summary` → 201, SCRUM-175)** `execute(caseNo, version)` → `CaseSummaryResult{case_no, summary{summary_lines, model_focus, produced_at, generator_version, based_on_case_version, is_current, input_sha256}, case_version}`. 클래스에 트랜잭션이 없어요(AI 대기 중 DB 트랜잭션·락 없음).
- **요청 모양 — 규약 v0.4 §6.1 (11/6)** `{case_no, alert, evidence_transaction_ids, reasons, prior_alert_count, reporting_institution}`. `alert`·`reasons`·`prior_alert_count`는 STR 초안 요청과 같은 값이라 detection `GetAlertForDraft`(#64, SCRUM-155)로 만들어요. `evidence_transaction_ids`는 경보 거래 전체(중복 제거·오름차순), 점수가 아직 없으면 `reasons`는 `[]`(AI는 null을 422로 거절), `reporting_institution`은 null(AI가 거래자 계좌 은행명). **메모는 넣지 않아요**(C6) — 메모만 바뀌면 `is_current`는 그대로.
- 순서: ① 읽기 트랜잭션 — 404 `CASE_NOT_FOUND` → 409 `CONCURRENT_UPDATE`(version) → 403 `NOT_ASSIGNEE` → 409 `INVALID_CASE_TRANSITION`(`isEditableByAssignee` 아님, `details.action = REFRESH_SUMMARY`) → 요청 구성·입력 해시. ② 트랜잭션 밖 `AiClient.caseSummary`. 실패는 AI 코드 그대로 502. 응답 `case_no`가 다르거나 `produced_at`·`generation_meta.prompt_version`·`code_checks`가 없거나 비었거나(AI 요약은 4종을 항상 줌) `summary_lines`가 없거나 6개를 넘으면 502 `AI_BAD_RESPONSE`. **`code_checks` 중 하나라도 `match=false`면 저장하지 않고 502 `SUMMARY_CODE_CHECK_FAILED`**(`details.failed_checks`, 〔제안 코드〕). ③ 저장 트랜잭션 — `findForUpdate` 뒤 ①의 검사를 다시 하고(그사이 종결·재배정이면 409) 요약 입력을 다시 만들어 해시가 다르면 409 `CONCURRENT_UPDATE`. 통과하면 `case_summaries` 한 행(응답 원문·입력 스냅샷·해시·`based_on_case_version` = 호출 전 version) + `cases.version` +1(`bumpVersion`, 이력 행 없음). `model_focus`("모델이 주목한 점" 한 줄, null 가능)는 `summary_lines`와 섞지 않고 따로 내보내요.
- 입력 해시(`CaseSummaryInput`): 요청 본문 JSON의 객체 키를 재귀 정렬, 공백 없이 UTF-8 SHA-256. 배열 순서는 그대로. 백엔드 안에서만 비교해요(AI와 맞출 벡터 없음).
- 요청·입력 해시는 `CaseSummaryInputs` 한 곳에서 만들어요 — 갱신과 `is_current` 판정이 같은 코드를 써야 해시가 맞아요.
- **`GetCaseSummary` — 구현됨 (API-13 `GET …/summary`, SCRUM-175)** 조회 3역할. `{case_no, summary}` — 요약이 없으면 `summary: null`. `is_current`는 지금 입력으로 다시 만든 해시와 비교(메모·상태만 바뀌면 그대로 true). 상세의 `latest_summary`도 같은 값(`latestOf`). **C7은 열람 기록을 남기지 않아요** — 케이스 열람 기록은 상세(API-02)가 남겨요(#79 리뷰).

**API** 〔초안 — CASES 명세 v0.5〕
| ID | API | 권한 |
|---|---|---|
| 01 | `GET /cases` (status 복수·assignee=me·from/to·정렬) | 조회 3역할 |
| 02 | `GET /cases/{case_no}` 통합 상세 (+ `available_actions`) | 조회 3역할, 열람 기록 |
| 03 | `POST /cases/{case_no}/assign` | 조사역 본인 · ADMIN 지정 |
| 04 | `POST /cases/{case_no}/reassign` | ADMIN |
| 05 | `PUT /cases/{case_no}/notes` | 담당 |
| 06 | `POST /cases/{case_no}/close` (초안 없을 때) | 담당 |
| 07 | `POST /cases/{case_no}/escalate` (사유 필수) | 담당 |
| 08 | `GET /cases/{case_no}/transactions?scope=EVIDENCE\|CONTEXT` | 조회 3역할 |
| 09 | `GET /cases/{case_no}/evidence/{transaction_id}` | 조회 3역할 |
| 10 | `GET /cases/{case_no}/graph` | 조회 3역할 |
| 11 | `GET /cases/{case_no}/histories` | 조회 3역할 |
| 12·13 | `POST …/refresh-summary`, `GET …/summary` | 담당 / 조회 3역할 |
| 14 | `POST /admin/batches/open-cases?date=` | ADMIN |
| 15 | `GET /cases/{case_no}/note-histories` | 조회 3역할 |

**화면 유스케이스 — 구현됨 (SCRUM-105)** 컨트롤러·응답 마스킹은 아래 SCRUM-106. 유스케이스는 엔티티가 아니라 record를 돌려주고, `account_key`는 내부 키 그대로라 응답에서 가린 값으로 바꿔요.

| 유스케이스 | API | 검사 순서·오류 |
|---|---|---|
| `ListCases` (readOnly) | 01 `GET /cases` | `status` 비면 전체, `assignee` = `me`·login_id(없는 login_id면 빈 목록), `from`·`to` = **한국 날짜** 케이스 생성일 양 끝 포함. 정렬 `score`·`created_at`·`status`, 동률 `created_at DESC, id DESC`. 형식 검사(size ≤ 100, 정렬 화이트리스트, from ≤ to)는 컨트롤러 |
| `GetCaseDetail` (readOnly 아님) | 02 `GET /cases/{case_no}` | 404 `CASE_NOT_FOUND`(기록 안 함) → `accessLog.view(CASE)` → 최근 이력 20건(최신 먼저, 작성자 login_id) · `available_actions` |
| `AssignCase` | 03 `POST …/assign` | 404 → version 409 `CONCURRENT_UPDATE` → 대상: 조사역이 남 지정 409 `INVALID_ASSIGNEE`(`details.reason = SELF_ONLY`), 없는 login_id 404 `USER_NOT_FOUND`, 비활성·조사역 아님 409 `INVALID_ASSIGNEE` → 상태 409 `INVALID_CASE_TRANSITION`. 조사역은 `assignee` 생략 = 본인, ADMIN은 필수 |
| `EscalateCase` | 07 `POST …/escalate` | 404 → version 409 → 담당 403 `NOT_ASSIGNEE` → 상태 409 → 사유 |
| `CloseCase` | 06 `POST …/close` | 404 → version 409 → 담당 403 → 상태 409 → **초안 있으면 409 `CLOSE_REQUIRES_STR`**(`Case.closeWithoutDraft`) → 사유. 초안 있는 종결(str `close-with-draft`)이 부를 공개 기능은 아직 없음 |
| `ReassignCase` (SCRUM-177) | 04 `POST …/reassign` `{assignee, comment?, version}` | **ADMIN만 — SecurityConfig URL 규칙(403 `FORBIDDEN`)** → 404 → version 409 → 대상: 없는 login_id 404 `USER_NOT_FOUND`, 비활성·조사역 아님 409 `INVALID_ASSIGNEE`(`NOT_ACTIVE_INVESTIGATOR`) → 상태 409(REVIEWING·ESCALATED·DRAFTED·REJECTED만). 같은 담당자면 no-op. 이력 REASSIGN에 `from_assignee_id`·`to_assignee_id`(V010)·사유. 대상 확인은 `AssignCase`와 같은 `ActiveInvestigators` |
| `CaseTransactions.list` (SCRUM-179) | 08 `GET …/transactions?scope&sort&page&size` | 404 → detection `ListAlertTransactions`(경보에 묶인 거래). `direction`은 케이스 계좌 기준 `TransactionDirection`(받음 INBOUND·보냄 OUTBOUND·둘 다 아님 INDIRECT, 자기계좌는 INBOUND). `scope=CONTEXT`는 저장소가 없어 빈 목록 〔참고 거래 등록 방식 미정〕. sort `ts`·`transaction_id` asc·desc, 계좌는 `AccountDisplay` |
| `CaseTransactions.evidence` (SCRUM-179) | 09 `GET …/evidence/{transaction_id}` | 404 `CASE_NOT_FOUND` → 이 경보의 거래가 아니면(없는 거래 포함) 404 `EVIDENCE_NOT_FOUND` → 열람 기록(`access_logs`에 거래 대상이 없어 CASE로) → `{case_no, transaction, source{dataset, record_id, is_synthetic}, eligible_for_str}` |
| `CaseTransactions.graph` (SCRUM-179) | 10 `GET …/graph?limit` | limit 기본 200(1~1,000), 시각 순. 노드 account_ref 중복 제거, 선은 거래마다(금액 = 받은 쪽). 넘치면 `truncated` |
| `ListCaseHistories` (SCRUM-179) | 11 `GET …/histories?action&page&size` | 최신 먼저, NOTE 제외(`action=NOTE`는 400 — C8로). 한 줄 모양은 상세 `history`와 같음 |
| 상세 `aggregates` (SCRUM-179) | 02 | 근거 거래 전체로 `CaseAggregates`: 통화별 유입(amount_received)·유출(amount_paid), 경유·자기계좌 거래 제외, 환산 없음. `transaction_count`·`first_ts`·`last_ts`는 전체 기준 |

- 세 변경 유스케이스는 공통 변경 응답(`CaseChangeResult` — 계약서 5.3 M)을 돌려줘요. `version`·`updated_at`은 flush 뒤 값. 요청 `version` 비교 뒤 커밋 때 `@Version`이 한 번 더 막아서 동시에 맡기면 뒤 요청은 409 `CONCURRENT_UPDATE`.
- **`available_actions` (`CaseAction`)** — 계약서 §4의 8개. `ASSIGN`(OPEN, 조사역·ADMIN) · `REASSIGN`(ADMIN, 재배정 가능 상태) · 담당만: `UPDATE_NOTES`·`REFRESH_SUMMARY`(REVIEWING·ESCALATED·DRAFTED·보완 반려) · `ESCALATE` · `CLOSE`/`CLOSE_WITH_DRAFT`(닫을 수 있는 상태에서 초안 유무로 하나) · `GENERATE_DRAFT`(초안을 붙일 수 있는 상태). 상태 판단은 **`CaseStatus.allows(CaseAction, 반려 코드)` 한 곳**이에요 — 상태를 바꾸는 동작은 전이 표 그대로, 상태가 그대로인 동작(재배정·메모·요약)은 같은 enum의 집합으로. `Case.reassign`·`isEditableByAssignee`도 이걸 불러요. 누가(역할·담당자)와 초안 유무는 `availableFor`·유스케이스가 더 봐요 [SCRUM-171, 3주차 리뷰 질문 2].
- **경보·점수 칸** — 상세 `alert`(alert_no·rule_id·batch_date·window_start·window_end·reference_ts·transaction_count)와 `latest_score`(score_status·score·score_band·failure_code·model_version·feature_version·data_quality_status·reasons)는 detection `GetAlertSummary`로 채워요(SCRUM-152·103). `latest_score`는 늘 있고, 점수 전(PENDING)·실패(FAILED)면 score_status만 있고 점수 칸은 null, reasons는 `[]`. `score_band`는 지금 운영 정책(`CasePolicy`)으로 계산 — 정책이 설정되지 않았으면 null(상세는 막지 않음, LOW로 바꾸지 않음). 계약서 C2에 없던 `transaction_count`·`failure_code`는 계약서에 한 줄씩 추가.
- **맨 위 `score`·`score_band`·`policy_version`** — 계약서 Q2 MVP대로 `latest_score.score`·`score_band`와 그때 쓴 정책 버전(#78 리뷰). 케이스 생성 때 저장하는 스냅샷은 후속.
- **아직 null인 칸 (후속)** — 목록의 `score`·`score_band`는 아직 null이에요(상세만 채움). 그래서 지금 `sort=score`는 "점수 전부 null"과 같은 결과(= 동률 규칙 `created_at DESC, id DESC`). `aggregates`·`investigator_notes`는 API-08·05, `current_draft`는 str 조합 계층(미결 17).
- 조사역이 다른 assignee를 지정하면 **409 `INVALID_ASSIGNEE`(`details.reason = SELF_ONLY`)** 로 확정(11/6). 명세 API-03은 400이었지만 400 `INVALID_REQUEST`는 형식 오류용이에요. 프론트는 `reason`으로 문구를 나눠요(`08_decisions.md` 11/6, SCRUM-153).

**컨트롤러 — 구현됨 (SCRUM-106)** `CaseController` `/api/v1/cases` — 01 목록 · 02 상세 · 03 배정 · 07 에스컬레이션 · 06 종료(초안 없음). 응답은 계약서 v0.2 C1·C2·5.3 모양이에요.
- **요청 검사(400 `INVALID_REQUEST`)** — 목록 `CaseListQuery`: `status` 쉼표 여럿(없는 값 400), `sort` = `score|created_at|status,asc|desc`, `page ≥ 0`, `1 ≤ size ≤ 100`(기본 20), `from ≤ to`. 변경 요청: `version` 필수, `comment` 공백 아님·2,000자 이하, `close_reason`은 세 값 중 하나. 상태·담당자 검사는 지금처럼 유스케이스·엔티티가 해요.
- **계좌(미결 19)** — 원본 `account_key`는 응답에 없어요. `account_no` = 은행ID + `****` + 끝 4자리(`010_****BD30`, 번호가 4자리 이하면 끝자리도 가림), `account_ref` = `ACR-` + `account_key` SHA-256 앞 12자리(같은 계좌는 어느 응답에서나 같은 값). 〔확인 필요〕 키 없는 해시라 계좌 목록이 있으면 대조할 수 있어요 — 발급 규칙은 SOURCE·공통 담당이 정하기로 했으니(CASES 명세 §5) 실데이터 전에 서버 비밀값 HMAC 같은 규칙으로 바꿔요. 형식은 그대로. `AccountDisplay` 한 곳만 고치면 돼요.
- **초안 번호** — `current_draft_id`(내부 id)는 `current_draft_no`(`DRF-000123`, `DisplayNumber.draftNo`)로 바꿔 보내요(계약서 Q14).
- **아직 null·[]인 칸**은 위 "아직 null인 칸"과 같아요. 키는 빼지 않아요(계약서 2.3). `customer`·`prior_alerts`도 후속.
- 남은 API: 04 재배정, 05 메모, 08~11·15 거래·근거·그래프·이력·메모 이력 — 각각 유스케이스가 생기면 이 컨트롤러에 붙여요.

**str에 주는 공개 기능 — 구현됨 (SCRUM-98)** 모두 `Propagation.MANDATORY`라 str 트랜잭션 안에서만 불러요(혼자 부르면 `IllegalTransactionStateException`).

| 유스케이스 | 하는 일 | 검사 순서·오류 |
|---|---|---|
| `AttachDraftToCase.lock(caseId, expectedVersion)` | 초안 insert **전에** 케이스를 잠그고 붙일 수 있는지만 확인 (아무것도 안 바꿈) | 케이스 잠금 조회 404 `CASE_NOT_FOUND` → version 409 `CONCURRENT_UPDATE` → 담당자 403 `NOT_ASSIGNEE` → 상태 409 `INVALID_CASE_TRANSITION`. 허용: ESCALATED, DRAFTED(재생성), REJECTED+NEED_REVISION |
| `AttachDraftToCase.execute(caseId, draftId, expectedVersion)` → 새 `cases.version` | 초안 insert **뒤에** 연결 → DRAFTED, 반려 코드 지움, `ATTACH_DRAFT` 이력. 돌려준 값을 str이 생성 응답 `case_version`에 실어요 [SCRUM-141] | `lock`과 같은 검사를 한 번 더 |
| `TouchCaseForRevision.execute(caseId, draftId, expectedVersion)` → 새 `cases.version` | 수정본 저장 전에 잠그고 확인한 뒤 케이스 version +1. **DRAFTED**면 version만 +1(상태·이력 그대로), **보완 반려(REJECTED+NEED_REVISION)**면 DRAFTED로 되돌리고 반려 코드 지움 + `REOPEN_DRAFT` 이력 (version은 상태 변경으로 +1, 두 번 안 올림). 돌려준 값을 str이 수정 응답 `case_version`에 실어요 [SCRUM-141] | 404 → version 409 → 담당자 403 → DRAFTED 또는 REJECTED+NEED_REVISION이고 `current_draft_id == draftId`(아니면 409 `INVALID_CASE_TRANSITION`, `details.action = REVISE_DRAFT`). 동시 승인 요청과 version으로 부딪히게 해요(NFR-APR-02, 미결 20). str은 상태를 볼 수 없어서 수정본 저장 때는 상태와 상관없이 이것 하나만 불러요 [#43 리뷰 2번, 11/5 채영 확인 A안] |

**str에 주는 공개 기능 — 승인 요청·승인·반려** 셋 다 `Propagation.MANDATORY`, 새 `cases.version`을 돌려줘요(str이 응답 `case_version`에 실음). str이 `approvals` 같은 케이스 FK 행을 넣기 **전에** 불러요(아래 "str 호출 순서"와 같은 이유 — FK insert의 KEY SHARE와 `FOR UPDATE`가 부딪혀 교착).

| 유스케이스 | 하는 일 | 검사 순서·오류 |
|---|---|---|
| `SubmitCaseForApproval.execute(caseId, draftId, expectedVersion)` | DRAFTED → PENDING_APPROVAL, `SUBMIT_FOR_APPROVAL` 이력 (S6) | 케이스 잠금 조회 404 `CASE_NOT_FOUND` → version 409 `CONCURRENT_UPDATE` → 담당자 403 `NOT_ASSIGNEE` → 현재 초안이 `draftId`(아니면 409 `INVALID_CASE_TRANSITION`, `details.current_draft_id`) → 상태 409 `INVALID_CASE_TRANSITION`. 본문 검증 통과·`NARRATIVE_UNCHANGED`는 str `ApprovalGate` 몫 |
| `ApproveCase.execute(caseId, draftId, expectedVersion)` | PENDING_APPROVAL → APPROVED, `APPROVE` 이력 (P2) | 404 → version 409 → 현재 초안 409 → 상태 409 → 승인자가 지금·예전 담당이면 409 `SELF_APPROVAL`(안에서 `WasCaseAssignee`를 불러요 — str이 따로 넘기지 않음). 역할(APPROVER)은 `SecurityConfig`·str `ApprovalGate.requireApprover` |
| `RejectCase.execute(caseId, draftId, expectedVersion, code, comment)` | PENDING_APPROVAL → REJECTED, 반려 코드 저장, `REJECT` 이력(`reason_code`·사유) (P3) | `ApproveCase`와 같고 마지막에 사유 필수(`CommentRequiredException`) |

- 승인자·담당자는 `CurrentUser`에서 꺼내요(인자로 안 받음). 동시에 온 수정본 저장(`TouchCaseForRevision`)과는 version으로 부딪혀 뒤 요청이 409.

- **str 호출 순서: `lock` → 초안 insert → `execute`** (수정본은 `TouchCaseForRevision`을 트랜잭션 맨 앞에서). `str_drafts` insert는 FK 때문에 `cases` 행에 KEY SHARE를 잡고, `findForUpdate`의 `FOR UPDATE`는 그것과 충돌해요. 초안을 먼저 넣고 잠그면 같은 케이스에 동시에 온 두 저장이 서로를 기다리다 교착(40P01)이 돼요. 맨 앞에서 잠그면 뒤 요청은 `lock`에서 기다렸다가 version 검사에서 409가 돼요. [#43 리뷰 1번]
- `draftId`는 같은 케이스의 초안이어야 해요(V008 `cases_current_draft_fk`). 이전 초안 SUPERSEDED는 str 몫이에요(`str_drafts_one_active_uq`).
- str은 `cases.domain`·`cases.infrastructure`가 아니라 이 유스케이스만 불러요. 담당자·상태 확인도 잠근 뒤 여기서 해요(str은 볼 방법이 없음). [#43 리뷰 2번]

**str에 주는 공개 조회 — 예전 담당 (V010, 미결 32)** 트랜잭션 전파는 기본(`REQUIRED`, 읽기 전용) — 승인 트랜잭션 안에서도, 초안 조회에서도 불러요.

| 유스케이스 | 하는 일 | 오류 |
|---|---|---|
| `WasCaseAssignee.execute(caseId, userId)` → `boolean` | 이 사람이 이 케이스를 **지금 맡고 있거나 예전에 맡은 적이 있나**. 지금 담당(`cases.assignee_id`) 또는 받은 사람(`case_history.to_assignee_id`) 이력. true면 승인·반려 버튼을 주지 않고(S2·승인함 `available_actions`), 승인·반려 유스케이스는 그 값을 `Case.approve`·`reject`에 넘겨요 → 409 `SELF_APPROVAL` | 없는 케이스 404 `CASE_NOT_FOUND` |

- **이력 칸 (V010)**: `ASSIGN`은 받은 사람(`to_assignee_id`)만, `REASSIGN`은 넘긴 사람(`from_assignee_id`)·받은 사람 둘 다. 그 밖의 행동은 둘 다 빈칸(CHECK). 필수 CHECK는 V010 전 이력 때문에 `NOT VALID` — 그래서 지금 담당은 `cases.assignee_id`로 따로 봐요.
- 승인함처럼 여러 건을 한 번에 볼 일이 많아지면 묶음 조회를 추가해요(지금은 건마다 한 번).

**str에 주는 공개 조회 — 승인 대기 목록 (승인함 P1, SCRUM-222)** 트랜잭션 전파는 기본(`REQUIRED`, 읽기 전용). str 조회 유스케이스가 `REPEATABLE READ`로 감싸 부르면 같은 스냅샷을 읽어요(08_decisions 10/30 조합 방식). `CaseRepository.search`는 이력 시각으로 정렬할 수 없고 생성일 기간이 필수라 따로 둬요.

| 유스케이스 | 하는 일 | 오류 |
|---|---|---|
| `ListCasesPendingApproval.execute(page, size)` → `Page<PendingCase>` | `status = PENDING_APPROVAL`만. `PendingCase(caseId, caseNo, version, currentDraftId, assignee, submittedAt)` — `submittedAt`은 그 케이스의 **마지막** `SUBMIT_FOR_APPROVAL` 이력 시각(보완 뒤 다시 요청하면 새 시각). 정렬은 `submittedAt` 오름차순, 같으면 case id 오름차순(계약서 P1 "기본 요청 오래된 순") — 고정이라 정렬 인자 없음 | page 음수·size 1~100 밖이면 `IllegalArgumentException`(형식 검사는 str 컨트롤러 몫, 계약서 2.3) |

- 검증·반려 정보(str 테이블)와 "직접 담당했던 케이스" 표시(`WasCaseAssignee`)는 str이 붙여요.
- 이력은 `case_history_case_action_idx (case_id, action, created_at desc)`로 찾아요. 승인 대기인데 `SUBMIT_FOR_APPROVAL` 이력이 없는 케이스(정상 흐름엔 없음)는 목록·건수 둘 다에서 빠져요.
- `submittedAt`은 DB 값 그대로(초 자르기 안 함) — 응답 형식은 str 몫.

**저장 매핑 (SCRUM-98)**
- `Case`는 이력을 컬렉션으로 들고 있지 않아요. 업무 메서드가 만든 이력은 `getPendingHistories()`에 쌓이고, 유스케이스가 `caseRepository.save(c)` 뒤 `caseHistoryRepository.saveAll(c.takePendingHistories())`로 같은 트랜잭션에서 넣어요.
- JPQL에서 `CASE`가 예약어라 엔티티 이름은 `InvestigationCase`예요(`select c from InvestigationCase c`).
- `case_history.case_version_before`는 **그 변경 직전**의 `cases.version`이에요. `@Version`은 flush 때 올라가서, 한 트랜잭션에서 전이가 두 번 일어나면 두 행에 같은 번호가 남아요.

**아직 남은 공개 기능** 〔초안 — CASES 명세 §6.2〕 `GetCaseEvidence`, 초안 있는 종결(명세 이름 `CloseCase` — 화면용 `CloseCase`가 이미 있어 str 담당과 이름을 맞춰요). Java 호출이고, str이 `Case` 메서드를 직접 부르지 않아요. 협력 트랜잭션은 str이 시작하고 cases가 참여(`REQUIRED`).

**〔확인 필요〕** 상세 화면에 "현재 초안"을 합치는 위치(cases → str 의존 금지라 상위 조합 계층 필요 — 권장은 str 조회 유스케이스가 조합. 같은 시점 보장은 한 SQL·REPEATABLE READ·조합 후 버전 재확인 중 하나로, 기본 READ COMMITTED에선 한 트랜잭션만으로는 부족 — 노션 미결 17번), 메모·요약을 편집할 수 있는 상태 범위, 화면 집계 규칙(유입은 받은 금액, 유출은 보낸 금액, 자기계좌 제외 — STR facts와 같은 규칙인지). AI facts는 **같은 법인의 다른 계좌**와 오간 거래를 `internal`로 따로 빼서 입출금에 넣지 않아요(dajaba-AI `docs/str/04_facts.md`). 그래서 같은 경보라도 화면과 STR 숫자가 다를 수 있어요 — 각 숫자가 무엇을 포함하는지 이름·설명을 정하고, 같은 법인 계좌 간 이체·복수 통화 사례로 같이 확인해요.
