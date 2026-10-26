# ⑤ 로그인·권한 — 세션 쿠키, CSRF, 두 겹 차단

사람이 읽는 설명은 「AML 계정·권한 안내」 문서에, 코드 쪽은 백엔드 설계 레퍼런스 9절에. 여기는 그 둘을 잇는 그림.

## 5-1. 로그인 → 보호된 API → 만료

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant FE as 프론트 (localhost:5173)
    participant SEC as SecurityConfig<br/>필터 체인
    participant UC as 유스케이스
    participant DB as PostgreSQL ops

    U->>FE: 아이디·비밀번호
    FE->>SEC: POST /api/v1/auth/login {login_id, password}
    SEC->>DB: users where login_id (is_active=true)
    alt 없음 / 비밀번호 불일치 / 비활성
        SEC->>DB: access_logs LOGIN_FAIL (user_id null 가능)
        SEC-->>FE: 401 {code: LOGIN_FAILED}  ⟵ 사유 구분 안 함
    else 일치 (BCrypt 12)
        SEC->>SEC: 기존 세션 있으면 끊음 (maximumSessions 1)
        SEC->>DB: access_logs LOGIN_OK
        SEC-->>FE: 200 {id, login_id, display_name, role}<br/>Set-Cookie: SESSION (HttpOnly, SameSite=Lax) · XSRF-TOKEN
    end

    note over FE: 새로고침 때마다 GET /auth/me 로 역할을 다시 받아 메뉴를 그린다
    FE->>SEC: GET /api/v1/auth/me (쿠키)
    SEC-->>FE: 200 {role: INVESTIGATOR}

    U->>FE: 케이스 배정 버튼
    FE->>SEC: POST /api/v1/cases/42/assign<br/>Cookie: SESSION · Header: X-XSRF-TOKEN
    SEC->>SEC: ① 세션 유효? ② CSRF 토큰 일치? ③ URL 역할 규칙?
    SEC->>UC: AssignCase(42)  — actor는 CurrentUser 빈에서
    UC->>UC: ④ 객체 규칙: case.isAssignedTo / 상태 전이 허용?
    UC->>DB: UPDATE cases, case_history
    UC-->>FE: 200

    note over U,DB: 30분 무활동 후
    FE->>SEC: GET /api/v1/cases
    SEC-->>FE: 401 {code: UNAUTHENTICATED}
    FE->>FE: 로그인 화면으로 (현재 경로 기억)

    U->>FE: 로그아웃
    FE->>SEC: POST /api/v1/auth/logout
    SEC->>DB: access_logs LOGOUT
    SEC-->>FE: 204, 쿠키 삭제
```

## 5-2. 권한이 막히는 네 지점 (어디서 무슨 코드가 나가나)

```mermaid
flowchart LR
    R[요청] --> A{① 세션 있나}
    A -- 없음/만료 --> E401[401 UNAUTHENTICATED]
    A -- 있음 --> B{② CSRF 토큰<br/>쓰기 요청만}
    B -- 불일치 --> E403c[403 CSRF_INVALID]
    B -- 일치 --> C{③ URL 역할<br/>SecurityConfig}
    C -- "/admin/** 인데 ADMIN 아님<br/>/approvals/** 인데 APPROVER 아님" --> E403[403 FORBIDDEN]
    C -- 통과 --> D{④ 객체 규칙<br/>유스케이스 안}
    D -- "담당 아님" --> E403a[403 NOT_ASSIGNEE]
    D -- "승인자 = 담당 조사역" --> E409s[409 SELF_APPROVAL]
    D -- "상태 전이 불가" --> E409[409 INVALID_TRANSITION]
    D -- 통과 --> OK[200 / 201]

    style E401 fill:#fde7e7,stroke:#c00
    style E403 fill:#fde7e7,stroke:#c00
    style E403c fill:#fde7e7,stroke:#c00
    style E403a fill:#fde7e7,stroke:#c00
    style E409 fill:#fff2cc,stroke:#b80
    style E409s fill:#fff2cc,stroke:#b80
    style OK fill:#e5f4e4,stroke:#383
```

프론트 행동 세 줄: **401** → 로그인 화면(경로 기억) · **403** → "권한이 없어요" 토스트, 화면 유지(버튼은 애초에 권한 표대로 숨김) · **409** → 메시지 표시 + 화면 새로고침(남이 먼저 바꾼 경우).

## 5-3. 계정 발급·역할 변경 (관리자, user 기능 W4)

```mermaid
sequenceDiagram
    autonumber
    actor Adm as 관리자
    participant FE as 프론트
    participant USR as user<br/>CreateUser · ChangeRole · DeactivateUser
    participant DB as PostgreSQL ops

    Adm->>FE: 계정 발급 (login_id, 이름, 역할, 초기 비밀번호)
    FE->>USR: POST /api/v1/admin/users
    USR->>USR: login_id 중복? 역할 하나만?
    USR->>DB: INSERT users (password_hash = BCrypt, is_active=true)
    USR->>DB: access_logs USER_CREATED (actor=관리자)
    USR-->>FE: 201 {id, login_id, role}
    note over Adm,FE: 초기 비밀번호는 관리자가 직원에게 직접 전달. 첫 로그인 변경 강제는 고도화

    Adm->>FE: 역할 변경 (조사역 → 승인자)
    FE->>USR: PATCH /api/v1/admin/users/{id}/role {role}
    USR->>DB: UPDATE users.role, access_logs ROLE_CHANGED (before→after)
    USR->>USR: 그 사용자의 세션 끊기 (다음 요청부터 새 역할)
    USR-->>FE: 200

    Adm->>FE: 비활성화 (퇴사)
    FE->>USR: PATCH /api/v1/admin/users/{id}/deactivate
    USR->>DB: UPDATE is_active=false, access_logs USER_DEACTIVATED
    USR->>USR: 세션 끊기
    note over USR,DB: 삭제는 없다 — case_history·approvals가 user_id를 가리키므로
```
