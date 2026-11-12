# 06. str — STR 초안 · 승인

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 담당 채영 · 정리 진희

담당자 문서는 아직 초안이라 `확정` / **〔초안〕** / **〔확인 필요〕**를 나눠 적었어요(뜻은 [`../README.md`](../README.md) "읽는 법"). 이 파일은 **담당자가 고쳐요.** 노션 초안을 바꾸면 같은 주에 이 파일도 PR로 갱신해 주세요.

## str — STR 초안·승인 (채영)

**하는 일** — 조사역이 에스컬레이션한 케이스에서 AI 초안을 받아 저장하고, 조사역 수정·검증·승인·반려를 거쳐 승인되면 잠가요. [STR 문서]

**원칙 세 가지** — ① 숫자(`facts`)는 AI도 사람도 못 고친다 ② **검증을 통과한 지금 본문**만 승인 요청할 수 있다 ③ 승인된 보고서는 다시 못 고친다.

**초안 상태** — `ACTIVE`(수정·검증은 여기서만) · `SUPERSEDED`(재생성되거나 케이스가 종료됨) · `LOCKED`(승인됨, 되돌릴 수 없음). 케이스 상태와 별개예요. [가이드 §4.4]

**확정** [안건 9·17·23·24, 규약 §5]
- **`facts`는 AI가 계산**해요. 백엔드는 거래 목록·facts를 보내지 않아요. 초안 요청 필드: `draft_id`, `case_no`, `alert`(규약 2절 경보), `evidence_transaction_ids`(**= `alert.transaction_ids` 전체**), `reasons`(점수 응답 그대로), `prior_alert_count`, `investigator_notes`, `reporting_institution`. [안건 23, 규약 §5.1] 〔STR 명세 예시의 `alert` 필드·`evidence` 부분집합 표기는 규약 원문을 따라 맞출 것〕
- **`draft_id`는 백엔드가 AI 호출 전에 먼저 발급**(시퀀스). 재전송은 같은 `draft_id`, 새 초안은 새 `draft_id`. `generation_id`는 AI가 발급해서 돌려주고 `str_drafts`에 저장. AI가 같은 `draft_id`를 처리 중이면 409 `GENERATION_IN_PROGRESS` — **자동 재시도 안 하고** "생성 중"으로 표시.
- `StrDraft`·`StrDraftRequest`는 shared `AssignedIdEntity` 상속, id는 `ops.str_draft_id_seq`에서 받은 값을 생성자로 넣어요(`str_draft_requests`는 `@AttributeOverride`로 `draft_id`). [SCRUM-68, `03_shared` §1]
- 초안 생성은 **ESCALATED · DRAFTED · REJECTED(보완 요청)**에서만. 에스컬레이션은 **별도 버튼**이고 초안 생성 요청 안에서 하지 않아요. [10/23 확정, 가이드 §5.1]
- **생성과 검증은 별도 버튼.** 초안 직후 자동 검증 없음. [안건 17]
- 수정은 섹션 `text`만. `facts`·`cites`·`evidence_ids`는 요청에 와도 무시하고 서버가 관리(문장을 고쳐도 그 단락의 `cites`·`evidence_ids`는 그대로). 저장 한 번 = `str_revisions` 한 행(전·후 본문). 섹션당 5,000자, 빈 섹션 금지.
- **통과 기준:** 코드 검사 **5종** 전부 통과 **그리고** 평가자 5종 전부 4점 이상. 평가자 호출 실패는 `VERIFY_UNAVAILABLE`(“검증 못 함”) — 승인 요청 불가, 자동 재시도 없음. [안건 9, 규약 v0.2 §5.3] 〔STR 문서·명세의 "코드 검사 4개"는 v0.1 표기〕
- **검증은 본문 해시에 묶어요.** 검증 요청 `{generation_id, revision_no, narrative}`, 응답에 `narrative_sha256`. 해시 규칙: 섹션(6섹션 V1 또는 서식 7항목 V2 — 한 세트만, 섞이면 거절)을 정해진 순서로, `section`·`text`만(`cites`·`evidence_ids` 제외), NFC 정규화, 공백 없는 JSON, UTF-8 SHA-256. Java·Python이 같은 값을 내는지 테스트 벡터(`narrative_hash.json`·`narrative_hash_v2.json`)로 확인. 수정본의 근거(`cites`·`evidence_ids`)는 검증 응답(`narrative_refs`)으로 다시 받아요 — **저장하지 않고 읽을 때 반영**: 지금 본문 해시와 같은 `narrative_sha256`의 최신 검증에 `narrative_refs`가 있으면 그 값, 없으면 수정본 값(`CurrentNarrative`, S2 본문·S5 검증 요청 공통). 수정본(`str_revisions`)은 사람이 고친 기록만 — S4 이력은 저장값 그대로. [안건 23, 진희 11/10 — 규약 §5.3 문장 변경 요청]
- **`cites`** = 단락마다 AI가 근거로 쓴 facts 키 목록(예: `period`, `inbound.count`). 초안 응답에 오고, **검증 요청에 섹션마다 저장된 값 그대로** 보내요 — 빠지면 근거 검사가 `CODE_FAILED`. [규약 v0.4 8번, AI SCRUM-62]
- **서술부 7항목 (11/6, 서식 안건 3·6)** — 새 초안은 `SUBJECT`·`DATE`·`PLACE`·`INSTRUMENT`·`METHOD`·`GROUNDS`·`OPINION`(AI `narrative_v2`). 옛 6섹션(`SUMMARY`…`CONCLUSION`) 초안·승인본은 그대로 둬요 — `StrSection`에 두 세트가 다 있고, 한 초안은 한 세트만(섞이면 생성·해시 거절, 초안에 없는 세트의 단락으로 수정하면 400). 단락 이름이 안 겹쳐서 이름만 보면 어느 세트인지 알아요. JSON 모양·버전 칸은 그대로.
- **반려 두 갈래** — 같은 API에 `reason_code`(`NEED_REVISION` 보완 요청 / `NOT_REPORTABLE` 보고 안 함) + `comment`, 둘 다 필수(없거나 틀리면 400). 반려 코드는 `approvals`(이력, 반려 당시 `narrative_sha256` 포함)와 `cases`(상태 규칙) 두 곳에 같은 트랜잭션으로. [안건 24]
- **승인 대기 중엔 수정 불가.** 고치려면 보완 요청으로 반려. [STR 문서]
- 승인 한 트랜잭션: `approvals` + 초안 `LOCKED` + 케이스 `APPROVED` + 이력. [레퍼런스 §3.3]

**`ApprovalGate`가 막는 여섯 경우** [STR 문서, 레퍼런스 §6.1 ②]
1. 지금 본문 해시와 같은 검증이 없음(검증 뒤에 고침) → `DraftNotVerifiedException`
2. 그 검증이 불통과이거나 `VERIFY_UNAVAILABLE` → `DraftNotVerifiedException`
   - 1·2번 공통: 같은 초안(`generation_id`)·같은 본문 해시의 검증이 여럿이면 **가장 최근 것만** 인정 — 그게 PASSED가 아니면 409 `DRAFT_NOT_VERIFIED`. 요청 `verification_id`가 그 최신과 다르면 같은 409. 평가자 점수가 매번 같지 않아서 다시 돌려 좋은 결과만 고르는 것을 막음 [백엔드 #22 리뷰 11/1]. 최신 고르기는 게이트가 직접 해요 — `ApprovalGate.requireVerified(draft, 같은 본문 검증 목록, verification_id)`(승인 P2는 번호 없이 `null`), 목록은 `VerificationResultRepository.findSameBody` [11/2 설계·코드 리뷰 질문 2 › 4번, SCRUM-215]
3. 다른 초안(`generation_id`)의 검증 → 거부
4. 보완 요청 반려 뒤 본문이 반려 당시와 같음 → 409 `NARRATIVE_UNCHANGED` — 게이트 안 `requireChangedSinceRejection`: 이 초안의 가장 최근 반려(`ApprovalRepository.findLatestRejection`)가 보완 요청이고 그 `narrative_sha256`이 지금 본문 해시와 같으면. 다른 초안(재생성)의 반려는 안 봐요. 승인 요청은 `requireSubmittable`이 사용 중 초안 → 4번 → 1·2·3번 순서로 한 번에
5. 승인자 역할 아님 → `NotApproverException` (403 NOT_APPROVER — ForbiddenException 상속, SCRUM-67, `ApprovalGate.requireApprover`) / 승인자가 지금 또는 **예전** 담당 → `SelfApprovalException` (409 `SELF_APPROVAL`, 게이트가 아니라 `Case.approve()`가 막음). **반려도 같아요**(`Case.reject()`). 케이스는 이력을 들고 있지 않아서, 승인·반려 유스케이스가 먼저 cases 공개 조회 `WasCaseAssignee.execute(caseId, approverId)`를 불러 그 결과를 `approve(approverId, wasAssignee)`·`reject(approverId, wasAssignee, code, comment)`로 넘겨요 (V010, 미결 32, SCRUM-174)
6. 케이스가 승인 대기가 아니거나 초안이 ACTIVE가 아님 → 거부

**API** 〔초안 — STR 명세〕
| # | API | 누가 |
|---|---|---|
| 1 | `POST /cases/{case_no}/str-drafts` (최대 90초) | 담당 |
| 2 | `GET /str-drafts/{draft_no}` | 담당·승인자는 본문, 관리자·비담당 조사역은 상태·날짜만(`META_ONLY`). 열람 기록 `strView` |
| 3 | `PUT /str-drafts/{draft_no}/revisions` `{case_version, sections:[{section, text}]}` (고친 섹션만) | 담당 |
| 4 | `GET /str-drafts/{draft_no}/revisions` (`?revision_no=` 전·후 본문) | 담당·승인자. 그 밖은 403 `NOT_ASSIGNEE`, 없는 번호는 404 `REVISION_NOT_FOUND` |
| 5 | `POST /str-drafts/{draft_no}/verify` (body `{}`) | 담당 |
| 6 | `POST /str-drafts/{draft_no}/submit` `{case_version, verification_id, suspicion_types, suspicion_level_branch}` | 담당, `DRAFTED`일 때만 |
| 7 | `POST /approvals/{draft_no}/approve` `{case_version, suspicion_level_hq}` | APPROVER (담당 본인 제외) |
| 8 | `POST /approvals/{draft_no}/reject` `{case_version, reason_code, comment}` | APPROVER (담당 본인 제외) |
| 9 | `POST /cases/{case_no}/close-with-draft` `{close_reason, comment, version}` (컨트롤러·유스케이스는 str) | 담당 |
| 10 | `POST /approvals/{draft_no}/report` `{case_version}` — 모의 제출 (P4) | APPROVER, `APPROVED`일 때 |
| 11 | `GET /str-drafts/{draft_no}/report/preview.pdf` — 제출 전 미리보기 (S7, 저장 안 함) | 담당·승인자, `APPROVED`일 때 |
| 12 | `GET /str-drafts/{draft_no}/report.pdf` — 확정본 (S8). 아직이면 409 `REPORT_PDF_NOT_READY` | 담당·승인자, `REPORTED`일 때. 열람 기록 `pdfDownload` |
| 13 | `POST /approvals/{draft_no}/report/pdf` — 다시 만들기 (P5) → 202 `{pdf_status}` | APPROVER, PDF `FAILED`일 때만 |

경로 번호는 `draft_no`(`DRF-000312`) [계약서 2.2, 11/3], `close-with-draft` 경로는 미결 2(10/29) 확정, `reasons`는 안건 3(10/28) 확정 — STR은 관측값만. **〔확인 필요〕** 조사역이 바뀐 케이스의 승인자 제한(논의 중).

**엔티티·저장 (SCRUM-138, 11/5)** — `StrDraftRequest`·`StrDraft`는 `AssignedIdEntity`(id = `draft_id`, 요청 기록은 `@AttributeOverride`로 `draft_id` 칸), `@Version`은 `StrDraft`만. `StrDraft`는 setter 없고 `protected` 기본 생성자, `facts`는 DB generated column이라 `insertable=false, updatable=false`. 수정본은 `StrDraft.revisions`(`@OneToMany` + `@JoinColumn(draft_id)`)에 추가만, 지금 본문 = 마지막 수정본. `str_drafts.current_revision_id`는 수정본 id가 INSERT 뒤에 나와서 `StrDraftRepositoryAdapter`(infrastructure)가 저장 직후 JPQL UPDATE로 따로 씀 — 칸은 `updatable = false`, 버전·`updated_at`은 안 바뀜(변경 감지로 쓰면 `@PreUpdate` auditing 때문에 버전이 한 번 더 오름). `StrRevision.created_by`는 auditor가 아니라 수정자를 직접 넣음 — 1번(AI 원문)은 SYSTEM(1). jsonb 본문 키는 `section`·`text`·`cites`·`evidence_ids` — `cites` 칸 전에 저장된 행은 키가 없어서 빈 목록으로 읽어요.

**초안 저장 `SaveStrDraft` (SCRUM-154, 11/6)** — AI 호출이 끝난 뒤 한 트랜잭션: ① `AttachDraftToCase.lock`(초안 INSERT보다 먼저 — 교착 대신 409, #43) → ② 요청 기록 확인(이 케이스의 `REQUESTED`인 같은 `draft_id`, 끝난 요청에 늦게 온 응답은 저장 안 함 — 미결 21 ⑤) → ③ 응답 읽기 `AiDraftResponseReader`(응답 `draft_id` = 요청 기록 `request_snapshot.draft_id`, `generator_version` = `generation_meta.prompt_version`, 단락 `section`·`text`·`cites`·`evidence_ids`) → ④ 재생성이면 예전 ACTIVE `SUPERSEDED` → ⑤ 초안 + 수정본 1번 INSERT → ⑥ `AttachDraftToCase.execute` → 새 `case_version` → ⑦ 요청 기록 `SUCCEEDED`. 실패하면 전부 롤백되고 요청 기록은 `REQUESTED`로 남아요 — `FAILED`로 바꾸는 건 `RequestStrDraft`가 별도 트랜잭션으로. 응답을 `AiDtos` 필드가 아니라 원문(`raw`)으로 읽어서 `AiDtos`가 바뀌어도 이 코드는 그대로예요.

**승인 요청 `SubmitStrDraft` (S6, 11/12)** — 한 트랜잭션: ① 초안 읽기(404) → ② cases `SubmitCaseForApproval`(케이스 잠금 → version 409 → 담당 403 → 현재 초안·DRAFTED 409, #98) → ③ `ApprovalGate.requireSubmittable`(사용 중 초안 → `NARRATIVE_UNCHANGED` → `DRAFT_NOT_VERIFIED`) → ④ `recordBranchSuspicion`(의심 유형·지점 정도). 케이스를 먼저 잠가서 담당이 아니면 검증을 보기 전에 403이고, 같은 케이스 본문 수정(S3)과 동시에 오면 한쪽은 409(미결 20). 보완 요청 반려 뒤 본문을 저장하지 않고 누르면 케이스가 REJECTED라 409 `INVALID_CASE_TRANSITION`, 같은 문장으로 저장(→ DRAFTED)한 뒤 누르면 409 `NARRATIVE_UNCHANGED`. 응답은 케이스 공통 변경 응답(계약서 5.3) — `StrCaseChangeReader`가 같은 트랜잭션에서 케이스를 다시 읽어 채워요.

**〔확인 필요 — 설계 피드백 10/29〕**
- **본문 수정 ↔ 승인 요청 동시 요청** — 수정은 `str_drafts`, 승인 요청은 `cases`를 바꿔서 버전 검사가 둘 다 통과할 수 있어요. 권장: 두 작업 모두 짧은 저장 트랜잭션에서 `cases` 행을 먼저 잠그고(AI 기다리는 동안은 안 잠금) 잠근 뒤 상태·담당자·버전·현재 본문을 다시 확인. 잠금만으로는 409가 안 나니 수정도 케이스 버전을 올리고, 뒤 요청은 잠금 후 요청 버전을 다시 비교. `narrative_refs`는 저장하지 않고 읽을 때만 쓰므로(11/10) 늦게 온 응답이 본문을 덮는 경합은 없어요 — 같은 초안(`draft_id`, 검증 행은 그 초안 `generation_id`로만 저장·FK)에서 **지금 본문 해시와 같은** 검증의 값만 써요(ApprovalGate와 같은 원칙). 테스트는 수정이 먼저·승인 요청이 먼저 두 경우. (채영·지윤, 노션 미결 20번)
- **재전송 때 같은 `draft_id`** — AI 생성은 성공했는데 응답을 놓치고 다시 누르면 새 `draft_id`가 나가서 AI 중복 방지가 안 걸려요. 같이 정할 것: ① 같은 작업 식별(재전송과 "다시 만들기" 구분) ② 응답 유실 복구 — 생성 중이면 409 `DRAFT_IN_PROGRESS`, 성공했으면 저장된 결과 반환, 실패가 확인됐을 때만 같은 ID로 재시도(타임아웃만으로 실패라고 단정 안 함) ③ 같은 ID엔 같은 입력(메모가 바뀌었으면 처음 입력 스냅샷 또는 새 작업) ④ 기존 ACTIVE 초안은 새 초안 저장이 성공하는 트랜잭션에서만 교체 ⑤ 오래된 작업을 정리한 뒤 늦게 끝난 결과가 덮어쓰지 않는 조건부 저장. 상태를 늘릴지 별도 요청 테이블을 둘지는 채영님 선택. (채영, 노션 미결 21번)

**〔미결 — 서식 회의 전〕** 승인 이후 `REPORTED` 상태와 3영업일 마감(서식 안건 1), 확정본 PDF(2), STR 열람 제한(4), `str_reports` 테이블(5-2·5-3).
