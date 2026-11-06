# 06. cases — 조사

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 담당 지윤 · 정리 진희

담당자 문서는 아직 초안이라 `확정` / **〔초안〕** / **〔확인 필요〕**를 나눠 적었어요(뜻은 [`../README.md`](../README.md) "읽는 법"). 이 파일은 **담당자가 고쳐요.** 노션 초안을 바꾸면 같은 주에 이 파일도 PR로 갱신해 주세요.

## cases — 조사 (지윤)

**하는 일** — 점수 높은 경보를 사람이 조사하는 업무(케이스)로 만들고, 담당자·근거·메모·상태·이력을 관리해요. 탐지와 STR 사이를 잇는 곳이에요. **HIGH 경보 하나당 케이스 최대 하나.** [CASES 문서 §1, 레퍼런스 §3.3]

**상태 9개 + 반려 코드** [가이드 §4, 안건 11·24]

`OPEN · REVIEWING · ESCALATED · DRAFTED · PENDING_APPROVAL · APPROVED · REPORTED · REJECTED · CLOSED` (`REPORTED`·`CLOSED`는 끝). `APPROVED`는 내부 승인 완료이고, 모의 제출(API 12-2)을 거쳐 `REPORTED`가 돼요. 반려 이유는 상태를 늘리지 않고 **별도 값** `rejection_reason_code`(`NEED_REVISION`·`NOT_REPORTABLE`)로 둬요. 〔초안: REJECTED일 때 필수, 다른 상태에선 null — CASES 명세 §2.1, 지우는 시점은 STR과 공유 필요〕

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
| PENDING_APPROVAL → APPROVED | `approve` | APPROVER | 담당 ≠ 승인자, 초안 LOCKED 같은 트랜잭션 |
| APPROVED → REPORTED | `report` 〔메서드는 API 12-2 구현 때 추가〕 | APPROVER | 승인 완료 건만. 제출 기록·Case REPORTED·이력 같은 트랜잭션(str 진행) |
| PENDING_APPROVAL → REJECTED | `reject(code, comment)` | APPROVER | 코드·사유 필수 |
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

**AI 조사 요약** 〔초안 — CASES 명세 API-12·13·§7.2〕 "DB → AI → DB" 패턴. 입력 스냅샷과 `input_sha256`을 저장해 `is_current`를 계산(케이스 version 차이로 계산하지 않음). 실패해도 조사는 계속(요약은 보조). AI 실패를 빈 요약·LOW로 바꾸지 않아요.

**`RefreshCaseSummary` — 구현됨 (API-12, 컨트롤러는 SCRUM-106)** `execute(caseNo, version)` → `CaseSummaryResult{case_no, summary{summary_lines, produced_at, generator_version, based_on_case_version, is_current, input_sha256}, case_version}`. 클래스에 트랜잭션이 없어요(AI 대기 중 DB 트랜잭션·락 없음).
- **요청 모양 — 규약 v0.4 §6.1 (11/6)** `{case_no, alert, evidence_transaction_ids, reasons, prior_alert_count, reporting_institution}`. `alert`·`reasons`·`prior_alert_count`는 STR 초안 요청과 같은 값이라 detection `GetAlertForDraft`(#64, SCRUM-155)로 만들어요. `evidence_transaction_ids`는 경보 거래 전체(중복 제거·오름차순), 점수가 아직 없으면 `reasons`는 `[]`(AI는 null을 422로 거절), `reporting_institution`은 null(AI가 거래자 계좌 은행명). **메모는 넣지 않아요**(C6) — 메모만 바뀌면 `is_current`는 그대로.
- 순서: ① 읽기 트랜잭션 — 404 `CASE_NOT_FOUND` → 409 `CONCURRENT_UPDATE`(version) → 403 `NOT_ASSIGNEE` → 409 `INVALID_CASE_TRANSITION`(`isEditableByAssignee` 아님, `details.action = REFRESH_SUMMARY`) → 요청 구성·입력 해시. ② 트랜잭션 밖 `AiClient.caseSummary`. 실패는 AI 코드 그대로 502. 응답 `case_no`가 다르거나 `produced_at`·`generation_meta.prompt_version`·`code_checks`가 없거나 `summary_lines`가 없거나 6개를 넘으면 502 `AI_BAD_RESPONSE`. **`code_checks` 중 하나라도 `match=false`면 저장하지 않고 502 `SUMMARY_CODE_CHECK_FAILED`**(`details.failed_checks`, 〔제안 코드〕). ③ 저장 트랜잭션 — `findForUpdate` 뒤 ①의 검사를 다시 하고(그사이 종결·재배정이면 409) 요약 입력을 다시 만들어 해시가 다르면 409 `CONCURRENT_UPDATE`. 통과하면 `case_summaries` 한 행(응답 원문·입력 스냅샷·해시·`based_on_case_version` = 호출 전 version) + `cases.version` +1(`bumpVersion`, 이력 행 없음).
- 입력 해시(`CaseSummaryInput`): 요청 본문 JSON의 객체 키를 재귀 정렬, 공백 없이 UTF-8 SHA-256. 배열 순서는 그대로. 백엔드 안에서만 비교해요(AI와 맞출 벡터 없음).
- 남은 것: API-13 `GET …/summary`(같은 `Summary` 모양 + 지금 입력으로 다시 만든 해시로 `is_current`), 상세의 `latest_summary`, 컨트롤러(SCRUM-106).

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

**화면 유스케이스 — 구현됨 (SCRUM-105)** 컨트롤러·응답 마스킹은 SCRUM-106. 유스케이스는 엔티티가 아니라 record를 돌려주고, `account_key`는 내부 키 그대로라 응답에서 가린 값으로 바꿔요.

| 유스케이스 | API | 검사 순서·오류 |
|---|---|---|
| `ListCases` (readOnly) | 01 `GET /cases` | `status` 비면 전체, `assignee` = `me`·login_id(없는 login_id면 빈 목록), `from`·`to` = **한국 날짜** 케이스 생성일 양 끝 포함. 정렬 `score`·`created_at`·`status`, 동률 `created_at DESC, id DESC`. 형식 검사(size ≤ 100, 정렬 화이트리스트, from ≤ to)는 컨트롤러 |
| `GetCaseDetail` (readOnly 아님) | 02 `GET /cases/{case_no}` | 404 `CASE_NOT_FOUND`(기록 안 함) → `accessLog.view(CASE)` → 최근 이력 20건(최신 먼저, 작성자 login_id) · `available_actions` |
| `AssignCase` | 03 `POST …/assign` | 404 → version 409 `CONCURRENT_UPDATE` → 대상: 조사역이 남 지정 409 `INVALID_ASSIGNEE`(`details.reason = SELF_ONLY`), 없는 login_id 404 `USER_NOT_FOUND`, 비활성·조사역 아님 409 `INVALID_ASSIGNEE` → 상태 409 `INVALID_CASE_TRANSITION`. 조사역은 `assignee` 생략 = 본인, ADMIN은 필수 |
| `EscalateCase` | 07 `POST …/escalate` | 404 → version 409 → 담당 403 `NOT_ASSIGNEE` → 상태 409 → 사유 |
| `CloseCase` | 06 `POST …/close` | 404 → version 409 → 담당 403 → 상태 409 → **초안 있으면 409 `CLOSE_REQUIRES_STR`**(`Case.closeWithoutDraft`) → 사유. 초안 있는 종결(str `close-with-draft`)이 부를 공개 기능은 아직 없음 |

- 세 변경 유스케이스는 공통 변경 응답(`CaseChangeResult` — 계약서 5.3 M)을 돌려줘요. `version`·`updated_at`은 flush 뒤 값. 요청 `version` 비교 뒤 커밋 때 `@Version`이 한 번 더 막아서 동시에 맡기면 뒤 요청은 409 `CONCURRENT_UPDATE`.
- **`available_actions` (`CaseAction`)** — 계약서 §4의 8개. `ASSIGN`(OPEN, 조사역·ADMIN) · `REASSIGN`(ADMIN, 재배정 가능 상태) · 담당만: `UPDATE_NOTES`·`REFRESH_SUMMARY`(REVIEWING·ESCALATED·DRAFTED·보완 반려) · `ESCALATE` · `CLOSE`/`CLOSE_WITH_DRAFT`(닫을 수 있는 상태에서 초안 유무로 하나) · `GENERATE_DRAFT`(초안을 붙일 수 있는 상태). 상태 판단은 `CaseStatus` 전이 표를 그대로 써요.
- **아직 null인 칸 (후속)** — `score`·`score_band`·`policy_version`·`alert`·`latest_score`는 detection에 경보·점수 공개 조회가 생겨야 채워요(SCRUM-152, 준혁). 그래서 지금 `sort=score`는 "점수 전부 null"과 같은 결과(= 동률 규칙 `created_at DESC, id DESC`). `aggregates`·`latest_summary`·`investigator_notes`는 API-08·12·05, `current_draft`는 str 조합 계층(미결 17).
- 조사역이 다른 assignee를 지정하면 **409 `INVALID_ASSIGNEE`(`details.reason = SELF_ONLY`)** 로 확정(11/6). 명세 API-03은 400이었지만 400 `INVALID_REQUEST`는 형식 오류용이에요. 프론트는 `reason`으로 문구를 나눠요(`08_decisions.md` 11/6, SCRUM-153).

**str에 주는 공개 기능 — 구현됨 (SCRUM-98)** 모두 `Propagation.MANDATORY`라 str 트랜잭션 안에서만 불러요(혼자 부르면 `IllegalTransactionStateException`).

| 유스케이스 | 하는 일 | 검사 순서·오류 |
|---|---|---|
| `AttachDraftToCase.lock(caseId, expectedVersion)` | 초안 insert **전에** 케이스를 잠그고 붙일 수 있는지만 확인 (아무것도 안 바꿈) | 케이스 잠금 조회 404 `CASE_NOT_FOUND` → version 409 `CONCURRENT_UPDATE` → 담당자 403 `NOT_ASSIGNEE` → 상태 409 `INVALID_CASE_TRANSITION`. 허용: ESCALATED, DRAFTED(재생성), REJECTED+NEED_REVISION |
| `AttachDraftToCase.execute(caseId, draftId, expectedVersion)` → 새 `cases.version` | 초안 insert **뒤에** 연결 → DRAFTED, 반려 코드 지움, `ATTACH_DRAFT` 이력. 돌려준 값을 str이 생성 응답 `case_version`에 실어요 [SCRUM-141] | `lock`과 같은 검사를 한 번 더 |
| `TouchCaseForRevision.execute(caseId, draftId, expectedVersion)` → 새 `cases.version` | 수정본 저장 전에 잠그고 확인한 뒤 케이스 version +1. **DRAFTED**면 version만 +1(상태·이력 그대로), **보완 반려(REJECTED+NEED_REVISION)**면 DRAFTED로 되돌리고 반려 코드 지움 + `REOPEN_DRAFT` 이력 (version은 상태 변경으로 +1, 두 번 안 올림). 돌려준 값을 str이 수정 응답 `case_version`에 실어요 [SCRUM-141] | 404 → version 409 → 담당자 403 → DRAFTED 또는 REJECTED+NEED_REVISION이고 `current_draft_id == draftId`(아니면 409 `INVALID_CASE_TRANSITION`, `details.action = REVISE_DRAFT`). 동시 승인 요청과 version으로 부딪히게 해요(NFR-APR-02, 미결 20). str은 상태를 볼 수 없어서 수정본 저장 때는 상태와 상관없이 이것 하나만 불러요 [#43 리뷰 2번, 11/5 채영 확인 A안] |

- **str 호출 순서: `lock` → 초안 insert → `execute`** (수정본은 `TouchCaseForRevision`을 트랜잭션 맨 앞에서). `str_drafts` insert는 FK 때문에 `cases` 행에 KEY SHARE를 잡고, `findForUpdate`의 `FOR UPDATE`는 그것과 충돌해요. 초안을 먼저 넣고 잠그면 같은 케이스에 동시에 온 두 저장이 서로를 기다리다 교착(40P01)이 돼요. 맨 앞에서 잠그면 뒤 요청은 `lock`에서 기다렸다가 version 검사에서 409가 돼요. [#43 리뷰 1번]
- `draftId`는 같은 케이스의 초안이어야 해요(V008 `cases_current_draft_fk`). 이전 초안 SUPERSEDED는 str 몫이에요(`str_drafts_one_active_uq`).
- str은 `cases.domain`·`cases.infrastructure`가 아니라 이 유스케이스만 불러요. 담당자·상태 확인도 잠근 뒤 여기서 해요(str은 볼 방법이 없음). [#43 리뷰 2번]

**저장 매핑 (SCRUM-98)**
- `Case`는 이력을 컬렉션으로 들고 있지 않아요. 업무 메서드가 만든 이력은 `getPendingHistories()`에 쌓이고, 유스케이스가 `caseRepository.save(c)` 뒤 `caseHistoryRepository.saveAll(c.takePendingHistories())`로 같은 트랜잭션에서 넣어요.
- JPQL에서 `CASE`가 예약어라 엔티티 이름은 `InvestigationCase`예요(`select c from InvestigationCase c`).
- `case_history.case_version_before`는 **그 변경 직전**의 `cases.version`이에요. `@Version`은 flush 때 올라가서, 한 트랜잭션에서 전이가 두 번 일어나면 두 행에 같은 번호가 남아요.

**아직 남은 공개 기능** 〔초안 — CASES 명세 §6.2〕 `GetCaseEvidence`, `SubmitCaseForApproval`, `ApproveCase`, `RejectCase`, 초안 있는 종결(명세 이름 `CloseCase` — 화면용 `CloseCase`가 이미 있어 str 담당과 이름을 맞춰요). Java 호출이고, str이 `Case` 메서드를 직접 부르지 않아요. 협력 트랜잭션은 str이 시작하고 cases가 참여(`REQUIRED`).

**〔확인 필요〕** 상세 화면에 "현재 초안"을 합치는 위치(cases → str 의존 금지라 상위 조합 계층 필요 — 권장은 str 조회 유스케이스가 조합. 같은 시점 보장은 한 SQL·REPEATABLE READ·조합 후 버전 재확인 중 하나로, 기본 READ COMMITTED에선 한 트랜잭션만으로는 부족 — 노션 미결 17번), 메모·요약을 편집할 수 있는 상태 범위, 화면 집계 규칙(유입은 받은 금액, 유출은 보낸 금액, 자기계좌 제외 — STR facts와 같은 규칙인지). AI facts는 **같은 법인의 다른 계좌**와 오간 거래를 `internal`로 따로 빼서 입출금에 넣지 않아요(dajaba-AI `docs/str/04_facts.md`). 그래서 같은 경보라도 화면과 STR 숫자가 다를 수 있어요 — 각 숫자가 무엇을 포함하는지 이름·설명을 정하고, 같은 법인 계좌 간 이체·복수 통화 사례로 같이 확인해요.
