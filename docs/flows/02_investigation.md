# ② 조사 — 로그인 → 목록 → 상세(+AI 요약) → 배정 → 에스컬레이션

조사역 한 명이 케이스 하나를 다루는 흐름. **cases 폴더는 str 폴더를 모른다**(의존 방향 `cases ← str`). 그래서 "현재 초안"은 케이스 상세 응답에 넣지 않고 **프론트가 따로 한 번 더 부른다**(지윤 문서 2절 "합칠 위치" 질문의 답).

```mermaid
sequenceDiagram
    autonumber
    actor Inv as 조사역 (inv01)
    participant FE as 프론트 (React)
    participant SEC as SecurityConfig<br/>세션·CSRF
    participant CAS as cases
    participant STR as str
    participant DET as detection (조회만)
    participant DB as PostgreSQL ops
    participant AI as AI 서버<br/>/v1/cases/summary

    Inv->>FE: 로그인
    FE->>SEC: POST /auth/login {login_id, password}
    SEC->>DB: users 조회, BCrypt 비교, access_logs LOGIN_OK
    SEC-->>FE: Set-Cookie SESSION + XSRF-TOKEN, {id, role}
    note over FE: 이후 모든 쓰기 요청에 X-XSRF-TOKEN 헤더 (axios 기본)

    Inv->>FE: 케이스 목록
    FE->>CAS: GET /cases?status=OPEN&sort=score,desc&page=0
    CAS->>DB: cases ⋈ alert_scores (점수순)
    CAS-->>FE: Page<CaseListItem> (계좌번호 마스킹)

    Inv->>FE: 케이스 클릭
    FE->>CAS: GET /cases/{id}
    CAS->>DET: 유스케이스 호출 — 경보·점수·reasons·근거 거래 ID
    DET-->>CAS: AlertDetailView
    CAS->>DB: cases, case_history, case_summaries(최신 1건)
    CAS->>DB: INSERT access_logs (CASE_VIEW, user_id, case_id)
    CAS-->>FE: CaseDetail {case, alert, score, reasons, history, summary?, current_draft_id}
    opt current_draft_id != null
        FE->>STR: GET /str/drafts/{current_draft_id}
        STR-->>FE: 초안 (narrative, revisions, 검증 결과)
        note over FE,STR: cases가 str을 역참조하지 않기 위해 프론트가 합친다
    end
    FE->>CAS: GET /cases/{id}/transactions?page=0 (근거 거래, 별도 페이징)
    CAS->>DET: 근거 거래 ID → source 거래 조회(읽기)
    CAS-->>FE: Page<TransactionRow>

    opt 요약이 없을 때 (또는 새로고침 버튼)
        FE->>CAS: POST /cases/{id}/summary:refresh
        CAS->>AI: POST /v1/cases/summary {경보·근거 거래 ID} (AiClient, 트랜잭션 밖, ≤90초)
        note over AI: facts는 AI가 원천을 읽어 계산 — 초안과 같은 규칙(안건 23)
        AI-->>CAS: {summary_text, model, tokens}
        CAS->>DB: INSERT case_summaries, ai_call_logs
        CAS-->>FE: 201 {summary}
        note right of AI: 실패해도 조사는 계속 — 요약 칸만 "생성 실패, 다시 시도"
    end

    Inv->>FE: "내게 배정"
    FE->>CAS: POST /cases/{id}/assign
    CAS->>CAS: Case.assign(currentUser) — OPEN→REVIEWING, @Version 검사
    CAS->>DB: UPDATE cases, INSERT case_history(ASSIGNED)
    CAS-->>FE: 200 {status: REVIEWING}

    alt 오탐으로 판단
        Inv->>FE: 종료 + 사유
        FE->>CAS: POST /cases/{id}/close {reason}
        CAS->>CAS: assertAssignedTo(me) · Case.close(reason) → CLOSED
        CAS->>DB: UPDATE, case_history(CLOSED)
    else 보고해야겠다
        Inv->>FE: 에스컬레이션
        FE->>CAS: POST /cases/{id}/escalate
        CAS->>CAS: assertAssignedTo(me) · Case.escalate() → ESCALATED
        CAS->>DB: UPDATE, case_history(ESCALATED)
        CAS-->>FE: 200 {status: ESCALATED}
        note over FE: 화면은 "초안 생성" 버튼을 보여준다 → ③ STR 흐름
    end
```

## 읽을 때 같이 볼 것

| 질문 | 답 |
|---|---|
| 상세 응답에 현재 초안을 어디서 합치나? (지윤) | 합치지 않는다. `CaseDetail`에는 `current_draft_id`만 있고, 프론트가 `GET /str/drafts/{id}`를 한 번 더 부른다. 호출 2번이 폴더 경계 지키는 값 |
| 남의 케이스를 열람하면? | 허용. 대신 `access_logs`에 남는다. **수정**(배정·종료·에스컬레이션·메모)만 담당자 제한 → `NotAssigneeException` 403 |
| 메모는 어디에? | 10/27 안건 16 — 기본안은 `case_history` NOTE 행 append |
| 요약 AI가 죽어 있으면? | 조사는 계속. 요약 칸만 실패 표시. [④ 실패 경로](04_failure_paths.md) |
