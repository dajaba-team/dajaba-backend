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
- 초안 생성은 **ESCALATED · DRAFTED · REJECTED(보완 요청)**에서만. 에스컬레이션은 **별도 버튼**이고 초안 생성 요청 안에서 하지 않아요. [10/23 확정, 가이드 §5.1]
- **생성과 검증은 별도 버튼.** 초안 직후 자동 검증 없음. [안건 17]
- 수정은 섹션 `text`만. `facts`·`evidence_ids`는 요청에 와도 무시하고 서버가 관리. 저장 한 번 = `str_revisions` 한 행(전·후 본문). 섹션당 5,000자, 빈 섹션 금지.
- **통과 기준:** 코드 검사 **5종** 전부 통과 **그리고** 평가자 5종 전부 4점 이상. 평가자 호출 실패는 `VERIFY_UNAVAILABLE`(“검증 못 함”) — 승인 요청 불가, 자동 재시도 없음. [안건 9, 규약 v0.2 §5.3] 〔STR 문서·명세의 "코드 검사 4개"는 v0.1 표기〕
- **검증은 본문 해시에 묶어요.** 검증 요청 `{generation_id, revision_no, narrative}`, 응답에 `narrative_sha256`. 해시 규칙: 섹션 6개를 정해진 순서로, `section`·`text`만(`evidence_ids` 제외), NFC 정규화, 공백 없는 JSON, UTF-8 SHA-256. Java·Python이 같은 값을 내는지 테스트 벡터로 확인. 수정본의 근거 번호는 검증 응답(`narrative_refs`)으로 다시 받아 서버가 반영할 수 있어요. [안건 23]
- **반려 두 갈래** — 같은 API에 `reason_code`(`NEED_REVISION` 보완 요청 / `NOT_REPORTABLE` 보고 안 함) + `comment`, 둘 다 필수(없거나 틀리면 400). 반려 코드는 `approvals`(이력, 반려 당시 `narrative_sha256` 포함)와 `cases`(상태 규칙) 두 곳에 같은 트랜잭션으로. [안건 24]
- **승인 대기 중엔 수정 불가.** 고치려면 보완 요청으로 반려. [STR 문서]
- 승인 한 트랜잭션: `approvals` + 초안 `LOCKED` + 케이스 `APPROVED` + 이력. [레퍼런스 §3.3]

**`ApprovalGate`가 막는 여섯 경우** [STR 문서, 레퍼런스 §6.1 ②]
1. 지금 본문 해시와 같은 검증이 없음(검증 뒤에 고침) → `DraftNotVerifiedException`
2. 그 검증이 불통과이거나 `VERIFY_UNAVAILABLE` → `DraftNotVerifiedException`
3. 다른 초안(`generation_id`)의 검증 → 거부
4. 보완 요청 반려 뒤 본문이 반려 당시와 같음 → 409 `NARRATIVE_UNCHANGED`
5. 승인자가 담당 본인 → `SelfApprovalException` / 승인자 역할 아님 → `NotApproverException`
6. 케이스가 승인 대기가 아니거나 초안이 ACTIVE가 아님 → 거부

**API** 〔초안 — STR 명세〕
| # | API | 누가 |
|---|---|---|
| 1 | `POST /cases/{case_no}/str-drafts` (최대 90초) | 담당 |
| 2 | `GET /str-drafts/{draft_id}` | 로그인 사용자 (열람 기록) |
| 3 | `PUT /str-drafts/{draft_id}/revisions` (고친 섹션만) | 담당 |
| 4 | `GET /str-drafts/{draft_id}/revisions` (`?revision_no=` 전·후 본문) | 로그인 사용자 |
| 5 | `POST /str-drafts/{draft_id}/verify` (body `{}`) | 담당 |
| 6 | `POST /str-drafts/{draft_id}/submit` `{case_version, verification_id}` | 담당 |
| 7 | `POST /approvals/{draft_id}/approve` | APPROVER |
| 8 | `POST /approvals/{draft_id}/reject` `{case_version, reason_code, comment}` | APPROVER |
| 9 | `POST /cases/{case_no}/close-with-draft` (컨트롤러·유스케이스는 str) | 담당 |

**〔확인 필요〕** 승인·반려 경로 `{draft_id}` vs `{case_no}`, `close-with-draft` 경로 확정(진희·지윤), 조사역이 바뀐 케이스의 승인자 제한(논의 중), `reasons` 형식(안건 3은 10/28 확정 — 관측·기준·기여 분리, STR은 관측값만).

**〔미결 — 서식 회의 전〕** 승인 이후 `REPORTED` 상태와 3영업일 마감(서식 안건 1), 확정본 PDF(2), 서술부 6섹션 → 서식 7항목(3), STR 열람 제한(4), `str_reports` 테이블(5-2·5-3).
