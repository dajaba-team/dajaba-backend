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
- `SCORED` · 미생성 경보 → CasePolicy로 분류 → **HIGH만** 케이스. 경보별로 `Case` + OPEN 이력 + `MarkCaseOpened`를 한 트랜잭션.
- `cases.alert_id` UNIQUE로 중복 방지. 성공한 날짜도 남은 대상 **보충 실행 허용**.

**메모** [안건 16 — 일부 미결]
- 결정표 권장은 `case_history`의 `NOTE` 행(메모 전체를 매번 저장). 지윤 의견은 `case_note_history` 분리. **이름(A/B)이 엇갈려서 최종 확인 필요** — 지윤·진희.
- 〔초안 — CASES 명세 API-05·15〕 NOTE 행 방식으로 전문 저장, 같은 내용은 no-op, 빈 문자열은 명시적 비우기, `Case.version`도 같이 올림.

**AI 조사 요약** 〔초안 — CASES 명세 API-12·13·§7.2〕 "DB → AI → DB" 패턴. 입력 스냅샷과 `input_sha256`을 저장해 `is_current`를 계산(케이스 version 차이로 계산하지 않음). 실패해도 조사는 계속(요약은 보조). AI 실패를 빈 요약·LOW로 바꾸지 않아요.

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

**str에 주는 공개 기능 — 구현됨 (SCRUM-98)** 모두 `Propagation.MANDATORY`라 str 트랜잭션 안에서만 불러요(혼자 부르면 `IllegalTransactionStateException`).

| 유스케이스 | 하는 일 | 검사 순서·오류 |
|---|---|---|
| `AttachDraftToCase.lock(caseId, expectedVersion)` | 초안 insert **전에** 케이스를 잠그고 붙일 수 있는지만 확인 (아무것도 안 바꿈) | 케이스 잠금 조회 404 `CASE_NOT_FOUND` → version 409 `CONCURRENT_UPDATE` → 담당자 403 `NOT_ASSIGNEE` → 상태 409 `INVALID_CASE_TRANSITION`. 허용: ESCALATED, DRAFTED(재생성), REJECTED+NEED_REVISION |
| `AttachDraftToCase.execute(caseId, draftId, expectedVersion)` | 초안 insert **뒤에** 연결 → DRAFTED, 반려 코드 지움, `ATTACH_DRAFT` 이력 | `lock`과 같은 검사를 한 번 더 |
| `TouchCaseForRevision.execute(caseId, draftId, expectedVersion)` | 수정본 저장 전에 잠그고 확인한 뒤 케이스 version +1. **DRAFTED**면 version만 +1(상태·이력 그대로), **보완 반려(REJECTED+NEED_REVISION)**면 DRAFTED로 되돌리고 반려 코드 지움 + `REOPEN_DRAFT` 이력 (version은 상태 변경으로 +1, 두 번 안 올림) | 404 → version 409 → 담당자 403 → DRAFTED 또는 REJECTED+NEED_REVISION이고 `current_draft_id == draftId`(아니면 409 `INVALID_CASE_TRANSITION`, `details.action = REVISE_DRAFT`). 동시 승인 요청과 version으로 부딪히게 해요(NFR-APR-02, 미결 20). str은 상태를 볼 수 없어서 수정본 저장 때는 상태와 상관없이 이것 하나만 불러요 [#43 리뷰 2번, 11/5 채영 확인 A안] |

- **str 호출 순서: `lock` → 초안 insert → `execute`** (수정본은 `TouchCaseForRevision`을 트랜잭션 맨 앞에서). `str_drafts` insert는 FK 때문에 `cases` 행에 KEY SHARE를 잡고, `findForUpdate`의 `FOR UPDATE`는 그것과 충돌해요. 초안을 먼저 넣고 잠그면 같은 케이스에 동시에 온 두 저장이 서로를 기다리다 교착(40P01)이 돼요. 맨 앞에서 잠그면 뒤 요청은 `lock`에서 기다렸다가 version 검사에서 409가 돼요. [#43 리뷰 1번]
- `draftId`는 같은 케이스의 초안이어야 해요(V008 `cases_current_draft_fk`). 이전 초안 SUPERSEDED는 str 몫이에요(`str_drafts_one_active_uq`).
- str은 `cases.domain`·`cases.infrastructure`가 아니라 이 유스케이스만 불러요. 담당자·상태 확인도 잠근 뒤 여기서 해요(str은 볼 방법이 없음). [#43 리뷰 2번]

**저장 매핑 (SCRUM-98)**
- `Case`는 이력을 컬렉션으로 들고 있지 않아요. 업무 메서드가 만든 이력은 `getPendingHistories()`에 쌓이고, 유스케이스가 `caseRepository.save(c)` 뒤 `caseHistoryRepository.saveAll(c.takePendingHistories())`로 같은 트랜잭션에서 넣어요.
- JPQL에서 `CASE`가 예약어라 엔티티 이름은 `InvestigationCase`예요(`select c from InvestigationCase c`).
- `case_history.case_version_before`는 **그 변경 직전**의 `cases.version`이에요. `@Version`은 flush 때 올라가서, 한 트랜잭션에서 전이가 두 번 일어나면 두 행에 같은 번호가 남아요.

**아직 남은 공개 기능** 〔초안 — CASES 명세 §6.2〕 `GetCaseEvidence`, `SubmitCaseForApproval`, `ApproveCase`, `RejectCase`, `CloseCase`. Java 호출이고, str이 `Case` 메서드를 직접 부르지 않아요. 협력 트랜잭션은 str이 시작하고 cases가 참여(`REQUIRED`).

**〔확인 필요〕** 상세 화면에 "현재 초안"을 합치는 위치(cases → str 의존 금지라 상위 조합 계층 필요 — 권장은 str 조회 유스케이스가 조합. 같은 시점 보장은 한 SQL·REPEATABLE READ·조합 후 버전 재확인 중 하나로, 기본 READ COMMITTED에선 한 트랜잭션만으로는 부족 — 노션 미결 17번), 메모·요약을 편집할 수 있는 상태 범위, 화면 집계 규칙(유입은 받은 금액, 유출은 보낸 금액, 자기계좌 제외 — STR facts와 같은 규칙인지). AI facts는 **같은 법인의 다른 계좌**와 오간 거래를 `internal`로 따로 빼서 입출금에 넣지 않아요(dajaba-AI `docs/str/04_facts.md`). 그래서 같은 경보라도 화면과 STR 숫자가 다를 수 있어요 — 각 숫자가 무엇을 포함하는지 이름·설명을 정하고, 같은 법인 계좌 간 이체·복수 통화 사례로 같이 확인해요.
