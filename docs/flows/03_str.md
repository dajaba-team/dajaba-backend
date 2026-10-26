# ③ STR — 에스컬레이션 → 초안 → 수정 → 검증 → 승인

str 폴더의 일. 세 가지 원칙이 그림에 그대로 보인다: **AI 호출은 트랜잭션 밖**(초안 요청·저장이 유스케이스 두 개), **숫자는 코드가 넣고 문장만 LLM이**, **검증 통과한 본문만 승인 요청·승인은 한 트랜잭션**.

```mermaid
sequenceDiagram
    autonumber
    actor Inv as 조사역 (담당)
    actor Apr as 승인자
    participant FE as 프론트
    participant STRC as str api<br/>StrDraftController
    participant REQ as str<br/>RequestStrDraft
    participant SAVE as str<br/>SaveStrDraft
    participant STRU as str<br/>Revise · Verify · Submit · Approve
    participant CAS as cases<br/>AttachDraftToCase · Case.*
    participant AI as AI 서버<br/>/v1/str/drafts · /v1/str/verify
    participant DB as PostgreSQL ops

    Inv->>FE: "초안 생성" (ESCALATED 케이스)
    FE->>STRC: POST /cases/{id}/str-drafts
    STRC->>REQ: RequestStrDraft(caseId)  ⟵ 트랜잭션 없음
    REQ->>DB: 케이스·경보·근거 거래 읽기 → facts (금액·건수·날짜는 코드가 계산)
    REQ->>AI: POST /v1/str/drafts {facts, evidence_transaction_ids} (≤90초, 마스킹 후)
    AI-->>REQ: {narrative[6 sections], prompt_version, model, usage}
    STRC->>SAVE: SaveStrDraft(caseId, 응답)  ⟵ @Transactional
    SAVE->>DB: 이전 ACTIVE 초안 → SUPERSEDED (재생성일 때)
    SAVE->>DB: INSERT str_drafts (payload jsonb 원문 + 추출 컬럼 3개, ACTIVE)
    SAVE->>CAS: AttachDraftToCase(caseId, draftId)  ⟵ cases 유스케이스
    CAS->>DB: cases.current_draft_id, status → DRAFTED, case_history(DRAFTED)
    SAVE->>DB: INSERT ai_call_logs
    STRC-->>FE: 201 {draft}
    note over FE: "검증하기" 버튼 강조 (자동 검증은 고도화 — 안건 17)

    loop 조사역이 고친다
        Inv->>FE: 섹션 text 수정 → 저장
        FE->>STRC: PUT /str-drafts/{id}/revisions {sections[].text, version}
        STRC->>STRU: ReviseStrDraft — ACTIVE인지, 담당인지, @Version
        STRU->>DB: INSERT str_revisions (revision_no+1), current_revision_id 갱신
        STRU-->>FE: 200 (다른 브라우저가 먼저 저장했으면 409 CONFLICT)
    end

    Inv->>FE: "검증"
    FE->>STRC: POST /str-drafts/{id}/verify
    STRC->>STRU: VerifyStrDraft  ⟵ 트랜잭션 밖
    STRU->>AI: POST /v1/str/verify {facts, narrative(현재 수정본)}
    AI-->>STRU: {code_checks[4], llm_checks[5], passed}
    STRU->>DB: INSERT verification_results (revision_id, passed, payload)
    STRU-->>FE: 200 {passed, 실패 항목}
    note right of AI: passed = 코드 4종 ∧ 평가자 5종 (안건 9). 호출 실패는 VERIFY_UNAVAILABLE

    Inv->>FE: "승인 요청"
    FE->>STRC: POST /str-drafts/{id}/submit
    STRC->>STRU: SubmitForApproval
    STRU->>STRU: ApprovalGate — 최신 수정본의 검증이 passed인가? 아니면 DraftNotVerifiedException(409)
    STRU->>CAS: Case.submitForApproval() → PENDING_APPROVAL
    STRU->>DB: UPDATE cases, case_history(SUBMITTED)
    STRU-->>FE: 200

    Apr->>FE: 승인자 로그인 → 승인 대기 목록 → 열람
    alt 승인
        Apr->>FE: "승인"
        FE->>STRC: POST /approvals/{draftId}/approve
        STRC->>STRU: ApproveStr  ⟵ @Transactional 하나
        STRU->>STRU: 승인자 == 담당 조사역? → SelfApprovalException(409)
        STRU->>DB: INSERT approvals(APPROVED)
        STRU->>DB: str_drafts.status → LOCKED
        STRU->>CAS: Case.approve() → APPROVED
        STRU->>DB: case_history(APPROVED)
        note over STRU,DB: 셋 중 하나라도 실패하면 전부 롤백
        STRU-->>FE: 200
    else 반려
        Apr->>FE: "반려" + 사유 (필수)
        FE->>STRC: POST /approvals/{draftId}/reject {reason}
        STRC->>STRU: RejectStr — 사유 없으면 CommentRequiredException(409)
        STRU->>DB: INSERT approvals(REJECTED, reason)
        STRU->>CAS: Case.reject() → REJECTED
        STRU-->>FE: 200
        note over Inv,FE: 조사역이 다시 수정하면 REJECTED → DRAFTED (안건 15) → 검증부터 다시
    end
```

## 읽을 때 같이 볼 것

| 질문 | 답 |
|---|---|
| 초안 직후 자동 검증 1회? (채영) | MVP는 안 함. 연속 AI 호출 2번 = 최대 180초. 안건 17. 그림은 "검증하기" 버튼 강조로 표현 |
| 승인 대기 중 수정? (채영) | 불가. PENDING_APPROVAL에서 `ReviseStrDraft`는 409. 고치려면 승인자가 반려 |
| 통과한 본문을 고치면? | 다시 검증해야 승인 요청 가능 — `ApprovalGate`가 "최신 수정본의 검증"을 본다 |
| 승인은 왜 한 트랜잭션? | 케이스 APPROVED·초안 LOCKED·approvals 행이 따로 성공하면 "승인됐는데 수정되는" 상태가 생긴다 |
| 초안 재생성하면? | 이전 초안 SUPERSEDED, 새 초안 ACTIVE, 케이스는 DRAFTED 유지. `AttachDraftToCase`가 두 번째 호출 |
