# ② 조사 — 로그인 → 목록 → 상세(+AI 요약) → 배정 → 에스컬레이션

조사역 한 명이 케이스 하나를 다루는 흐름. **cases 폴더는 str 폴더를 모른다**(의존 방향 `cases ← str`). 그래서 "케이스 상세 + 현재 초안"은 **str 쪽 조회 유스케이스가 cases 공개 상세 조회를 불러 조합**하고(str → cases 방향만), 화면 요청은 하나다(미결 17, 10/30).

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
    FE->>STR: GET /cases/{id} (화면 요청 하나)
    note over STR,DB: 짧은 읽기 전용 REPEATABLE READ 트랜잭션 — 케이스와 초안을 같은 시점으로 (미결 17)
    STR->>CAS: 공개 상세 조회 (str → cases)
    CAS->>DET: 유스케이스 호출 — 경보·점수·reasons·근거 거래 ID
    DET-->>CAS: AlertDetailView
    CAS->>DB: cases, case_history, case_summaries(최신 1건)
    CAS-->>STR: 케이스 상세 DTO {case, alert, score, reasons, history, summary?, current_draft_id, version}
    alt current_draft_id != null
        STR->>DB: 현재 초안 (narrative, revisions, 검증 결과)
        STR->>STR: 열람 권한 — 본문은 담당 조사역·승인자만, 그 밖에는 상태·날짜만 (서식 안건 4)
    else 초안 없음
        note over STR: current_draft = null (정상 조회)
    end
    STR->>DB: INSERT access_logs (VIEW, 본문을 봤으면 STR 본문 열람도 — 이름은 SCRUM-19)
    STR-->>FE: CaseDetail {case, alert, score, reasons, history, summary?, current_draft}
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
    CAS->>DB: UPDATE cases, INSERT case_history(ASSIGN)
    CAS-->>FE: 200 {status: REVIEWING}

    alt 오탐으로 판단
        Inv->>FE: 종료 + 사유
        FE->>CAS: POST /cases/{id}/close {reason}
        CAS->>CAS: assertAssignedTo(me) · Case.close(reason) → CLOSED
        CAS->>DB: UPDATE, case_history(CLOSE)
    else 보고해야겠다
        Inv->>FE: 에스컬레이션
        FE->>CAS: POST /cases/{id}/escalate
        CAS->>CAS: assertAssignedTo(me) · Case.escalate() → ESCALATED
        CAS->>DB: UPDATE, case_history(ESCALATE)
        CAS-->>FE: 200 {status: ESCALATED}
        note over FE: 화면은 "초안 생성" 버튼을 보여준다 → ③ STR 흐름
    end
```

## 읽을 때 같이 볼 것

| 질문 | 답 |
|---|---|
| 상세 응답에 현재 초안을 어디서 합치나? (지윤) | **str 쪽 조회 유스케이스**가 cases 공개 상세 조회를 불러 조합한다. CASES는 초안 참조 ID·버전과 케이스 정보를 DTO로 주고 str을 역호출하지 않는다. 시점은 짧은 읽기 전용 REPEATABLE READ 트랜잭션, 화면 요청은 하나, 초안이 없으면 `current_draft = null` (미결 17, 10/30) |
| 남의 케이스를 열람하면? | 허용. 대신 `access_logs`에 남는다. **수정**(배정·종료·에스컬레이션·메모)만 담당자 제한 → `NotAssigneeException` 403. **예외 — 현재 초안 본문**은 담당 조사역·승인자만, 그 밖에는 상태·날짜만 (서식 안건 4) |
| 메모는 어디에? | `case_history` NOTE 행, 별도 테이블 없음 (미결 8, 10/30) |
| `case_history` action 이름 | 동사형 — `OPEN`·`ASSIGN`·`REASSIGN`·`NOTE`·`ESCALATE`·`CLOSE` (그림의 이전 `ASSIGNED`·`CLOSED`·`ESCALATED`는 옛 표기, 미결 8) |
| 요약 AI가 죽어 있으면? | 조사는 계속. 요약 칸만 실패 표시. [④ 실패 경로](04_failure_paths.md) |
