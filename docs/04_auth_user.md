# 04. 계정·권한·로그인 (`user`, `shared/security`)

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

이 문서의 근거는 [계정·권한]과 [레퍼런스 §9]예요. 둘이 다르면 **레퍼런스 §9가 맞아요**(계정·권한 문서에 그렇게 적혀 있어요). 그림은 `docs/flows/05_login_auth.md`.

## 1. 계정 — 회원가입은 없다 [계정·권한 §1·§6]

은행 내부 시스템이라 **관리자가 계정을 발급**해요. 회원가입·이메일 인증·비밀번호 찾기 화면이 없어요.

| 언제 | 누가 | 무엇을 | 남는 기록 |
|---|---|---|---|
| 입사·배치 | 관리자 | 계정 만들기 (로그인 ID, 이름, 역할 1개, 초기 비밀번호) | `users` 1행, `access_logs` USER_CREATED |
| 업무 변경 | 관리자 | 역할 바꾸기 | `access_logs` ROLE_CHANGED(전→후), 그 사람 세션 끊김 |
| 퇴사·휴직 | 관리자 | 비활성화 (`is_active = false`) | `access_logs` USER_DEACTIVATED. **삭제 없음** |
| 시연·개발 | 시스템 | 시드 계정 자동 생성 (§6) | — |

- `users` 컬럼: `id`, `login_id`(유일), `password_hash`(BCrypt strength 12), `display_name`, `role`, `is_active`, 감사 4. 이메일·전화 없음.
- `id = 1`은 `SYSTEM`(배치용, 로그인 불가, `password_hash` null) — `V003`에서 넣어요.
- 〔확인 필요: `access_logs.action`은 레퍼런스 §3.4에 `VIEW, EXPORT, LOGIN_OK, LOGIN_FAIL, LOGOUT`만 있어요. 계정·권한 §1·§6의 `USER_CREATED`·`ROLE_CHANGED`·`USER_DEACTIVATED`를 넣으려면 `access_logs` 마이그레이션 쓸 때 CHECK 값에 추가해야 해요〕

## 2. 역할 세 개 — 한 사람에 하나 [계정·권한 §2, 레퍼런스 §9.2]

| 역할 | 코드 | 하는 일 | 하지 않는 일 |
|---|---|---|---|
| 조사역 | `INVESTIGATOR` | 케이스를 맡아 조사, 종료·에스컬레이션, STR 초안 생성·수정·검증·승인 요청 | 승인. 남의 케이스 수정(보기는 됨) |
| 승인자 | `APPROVER` | 승인 요청된 보고서를 승인·반려 | 조사·초안 작성·수정. 자기가 담당했던 케이스 승인 |
| 관리자 | `ADMIN` | 적재·배치 실행, 룰 기준값, 계정 관리, 담당자 배정·교체, 로그 조회 | 조사·초안·승인 |
| (시스템) | `SYSTEM` | 배치가 만든 행의 `created_by` | 로그인 |

**규칙 세 개**
1. **한 사람에 역할 하나.** 조사도 하고 승인도 하는 계정은 없어요.
2. **승인자 ≠ 담당 조사역**은 케이스 단위 규칙이에요. 역할이 승인자여도 그 케이스 담당이었다면 409 `SELF_APPROVAL`(`ApprovalGate`가 막음).
3. **조회는 열려 있고, 수정만 담당자로 제한.** 대신 열람은 `access_logs`에 남아요.

## 3. 로그인 — 세션 쿠키 [계정·권한 §3, 레퍼런스 §9.1·§9.3, 코드 `SecurityConfig`]

JWT를 쓰지 않아요. 서버가 즉시 끊을 수 있는 **세션 쿠키**예요.

| 단계 | 프론트 | 서버 |
|---|---|---|
| 로그인 | `POST /api/v1/auth/login` `{login_id, password}` | BCrypt 비교 → `SESSION`(HttpOnly) + `XSRF-TOKEN` 쿠키 + `{id, login_id, display_name, role}`. `access_logs` LOGIN_OK |
| 실패 | 메시지 한 가지 | 401 `LOGIN_FAILED` — 없는 ID·틀린 비밀번호·비활성 **모두 같은 응답**. `access_logs` LOGIN_FAIL (`login_id_attempted` 기록) |
| 이후 요청 | 쓰기 요청(POST/PUT/PATCH/DELETE)에 `X-XSRF-TOKEN` 헤더 (axios 기본) | 세션 → CSRF → URL 역할 순서로 확인 |
| 새로고침 | `GET /api/v1/auth/me`로 역할 다시 받기. localStorage에 역할 저장 안 함 | `{id, login_id, display_name, role}` |
| 만료 | 401이 오면 로그인 화면, 경로 기억 | **30분 무활동** → 401 `UNAUTHENTICATED` |
| 로그아웃 | `POST /api/v1/auth/logout` | 세션 무효화, 쿠키 삭제, LOGOUT |

- **동시 세션 1개** (`maximumSessions(1)`) — 두 번째 로그인이 먼저 것을 끊어요.
- **CSRF 켬.** `CookieCsrfTokenRepository.withHttpOnlyFalse()`.
- **CORS** — `local` 프로파일에서만 `localhost:5173` 하나를 `allowCredentials(true)`. `*`는 어디에도 없음. 프론트는 `withCredentials: true`.
- 쿠키 `Secure`는 `prod`에서만. 세션 저장소는 서버 메모리(재시작하면 모두 로그아웃 — 받아들임).
- 로그인 성공·실패 기록은 `shared/security`의 성공·실패 핸들러에서 남겨요. [레퍼런스 §3.4]
- 로그인 5회 실패 잠금은 MVP 제외(기록만). [계정·권한 §3]
- 지금은 `POST /api/v1/auth/mock-login?login_id=`(local 전용, 비밀번호 안 봄)이 있어요. **진짜 로그인이 머지되는 PR에서 `MockLoginController`를 지워요.** 다른 사람 코드는 `CurrentUser`만 쓰니까 안 바뀌어요. [README]

## 4. 권한 표 [계정·권한 §4]

프론트는 ○가 아닌 버튼을 숨기고, 백엔드는 ○가 아닌 요청에 403을 줘요. 괄호는 "조건부 ○" — URL로 못 막고 유스케이스가 확인해요.

| # | 기능 | API | 조사역 | 승인자 | 관리자 |
|---|---|---|---|---|---|
| 1 | 로그인 · 내 정보 · 로그아웃 | `/auth/*` | ○ | ○ | ○ |
| 2 | 케이스 목록 | `GET /cases` | ○ | ○ | ○ |
| 3 | 케이스 상세 | `GET /cases/{case_no}` | ○ (열람 기록) | ○ | ○ |
| 4 | 근거 거래 목록 | `GET /cases/{case_no}/transactions` | ○ | ○ | ○ |
| 5 | AI 요약 생성·새로고침 | 아래 경로 주의 | ○ (담당만) | — | — |
| 6 | 내게 배정 | `POST /cases/{case_no}/assign` | ○ (OPEN만) | — | ○ (누구에게나) |
| 7 | 담당자 교체 | `POST /cases/{case_no}/reassign` | — | — | ○ |
| 8 | 메모 작성 | 아래 경로 주의 | ○ (담당만) | — | — |
| 9 | 종료 (사유 필수) | `POST /cases/{case_no}/close` | ○ (담당만) | — | — |
| 10 | 에스컬레이션 | `POST /cases/{case_no}/escalate` | ○ (담당만, REVIEWING) | — | — |
| 11 | STR 초안 생성·재생성 | `POST /cases/{case_no}/str-drafts` | ○ (담당만, ESCALATED·DRAFTED·보완 요청 반려) | — | — |
| 12 | 초안 열람 | `GET /str-drafts/{draft_id}` | ○ | ○ | ○ |
| 13 | 초안 수정 | `PUT /str-drafts/{draft_id}/revisions` | ○ (담당만, ACTIVE·승인 대기 아님) | — | — |
| 14 | 검증 | `POST /str-drafts/{draft_id}/verify` | ○ (담당만) | — | — |
| 15 | 승인 요청 | `POST /str-drafts/{draft_id}/submit` | ○ (담당만, 현재 본문 검증 통과) | — | — |
| 16 | 승인 대기 목록 | `GET /approvals` | — | ○ | ○ (조회만) |
| 17 | 승인 | `POST /approvals/{draft_id}/approve` | — | ○ (담당 본인 제외) | — |
| 18 | 반려 (`reason_code` + `comment` 필수) | `POST /approvals/{draft_id}/reject` | — | ○ (담당 본인 제외) | — |
| 19 | 데이터 적재 | `POST /admin/batches/load` | — | — | ○ |
| 20 | 룰 스캔·점수·케이스 생성 배치 | `POST /admin/batches/*` | — | — | ○ |
| 21 | 배치 상태 | `GET /admin/batches/{id}` | — | — | ○ |
| 22 | 룰 기준값 | `GET/PUT /admin/rules` | — | — | ○ |
| 23 | 계정 관리 | `/admin/users*` | — | — | ○ |
| 24 | 접근 로그·AI 호출 로그 | `GET /admin/access-logs`, `/admin/ai-calls` | — | — | ○ |
| 25 | swagger · health | `/swagger-ui.html`, `/actuator/health` | local에서만, 역할 무관 | | |

- 6번 관리자 ○는 레퍼런스 §9.2·CASES 명세 API-03 기준이에요(계정·권한 표는 —).
- 〔확인 필요 — 경로가 문서마다 다른 것. 최신 담당자 초안 기준으로 적되, 확정은 담당자끼리〕
  - 5번 AI 요약: 계정·권한 `POST /cases/{id}/summary:refresh` → CASES 명세 v0.5 `POST /cases/{case_no}/refresh-summary` (+ `GET …/summary`)
  - 8번 메모: 계정·권한 `POST /cases/{id}/notes` → CASES 명세 `PUT /cases/{case_no}/notes`
  - 7번 교체 본문: `{assignee_id}` → CASES 명세 `{assignee: login_id, comment, version}`
  - 9번 종료: 초안이 **없으면** `POST /cases/{case_no}/close`, **있으면** `POST /cases/{case_no}/close-with-draft`(str 컨트롤러) — CASES·STR 명세 공동 제안
  - 17·18번: 계정·권한·STR 명세는 `{draft_id}`, CASES 명세는 `{case_no}` 유지안 제안
- 〔미결: 서식 안건 4 — 초안 본문 열람을 "조사역은 담당만, 관리자는 상태·날짜만"으로 좁히고, 확정본 PDF 다운로드·모의 제출 행을 추가하는 안. 회의 전〕

**읽는 법** — 관리자는 배정·교체는 하지만 조사·초안·승인은 안 해요. 승인자는 읽고 결정만. "담당만"은 조사역 사이의 규칙이라, 남의 케이스를 직접 호출하면 403 `NOT_ASSIGNEE`. 프론트가 버튼을 정할 때는 `/auth/me`의 `role` + 케이스의 `assignee` + `status` 세 값을 봐요. 서버는 매번 다시 검사해요(버튼 숨김은 편의일 뿐).

## 5. 요청이 막히는 네 지점 [계정·권한 §5, 레퍼런스 §9.2]

| 순서 | 어디서 | 무엇을 보나 | 막히면 | 프론트 |
|---|---|---|---|---|
| ① | 세션 | 쿠키가 살아 있나 | 401 `UNAUTHENTICATED` | 로그인 화면으로 |
| ② | CSRF | 쓰기 요청 헤더가 쿠키와 같나 | 403 `CSRF_INVALID` | "다시 시도" |
| ③ | URL 역할 | `/api/v1/admin/**` ADMIN, `/api/v1/approvals/**` APPROVER, 나머지 `/api/**` 로그인만 | 403 `FORBIDDEN` | 토스트 |
| ④ | 유스케이스 | 담당인가 / 자기 승인인가 / 전이가 되나 / 남이 먼저 바꿨나 | 403 `NOT_ASSIGNEE` · 409 `SELF_APPROVAL` · 409 `INVALID_CASE_TRANSITION` · 409 `NARRATIVE_UNCHANGED` · 409 `CONCURRENT_UPDATE` | 403은 토스트, 409는 메시지 + 새로고침 (`NARRATIVE_UNCHANGED`만 "본문을 고친 뒤 다시 요청") |

①~③은 `SecurityConfig` 한 파일, ④는 각 유스케이스(객체 단위 — `case.assertAssignedTo(currentUser.id())`). 다른 사람은 `SecurityConfig`를 고칠 일이 거의 없어요.

## 6. 개발·시연 계정 [레퍼런스 §9.5, 계정·권한 §7, 코드 `DevUserSeeder`]

`local` 프로파일에서만 `DevUserSeeder`가 만들어요(이미 있으면 건너뜀). 비밀번호는 환경변수 `DEV_USER_PASSWORD` 하나. 마이그레이션엔 `SYSTEM`만 있어요(비밀번호 해시를 git에 남기지 않으려고).

| 로그인 ID | 역할 | 시연에서 |
|---|---|---|
| `admin` | ADMIN | 배치 버튼, 담당자 교체, 계정 발급 |
| `inv01` | INVESTIGATOR | 배정 → 에스컬레이션 → 초안 → 수정 → 검증 → 승인 요청 |
| `apr01` | APPROVER | 승인 · 반려 |
| `inv02` | INVESTIGATOR | "남의 케이스 → 403", "동시 수정 → 409" 시연용 |

## 7. 관리자의 `user` 기능 범위 — 이게 전부 [계정·권한 §6]

| 기능 | API | 규칙 |
|---|---|---|
| 계정 목록 | `GET /admin/users` | 비활성 포함, 필터 `role`, `is_active` |
| 계정 발급 | `POST /admin/users {login_id, display_name, role, initial_password}` | `login_id` 유일, 역할 3개 중 하나, 서버에서 BCrypt. 응답에 비밀번호 없음 |
| 역할 변경 | `PATCH /admin/users/{id}/role {role}` | ROLE_CHANGED, 그 사용자 세션 끊김 |
| 비활성화 / 재활성화 | `PATCH /admin/users/{id}/deactivate` · `/activate` | 세션 끊김. 담당 케이스가 있으면 응답에 개수 |
| 비밀번호 초기화 | `POST /admin/users/{id}/reset-password {new_password}` | 세션 끊김 |

- 관리자가 안 하는 것: 자기 역할 변경, 자기 비활성화, 계정 삭제(기능 없음).
- 엔티티는 `User`, `Role` 둘. 검사는 ID 중복·역할 값·비활성 사용자 로그인 거부 세 개. 테스트는 그 세 개 + "역할 바꾸면 세션이 끊긴다".
- 〔확인 필요: 계정·권한 §6은 역할 변경 등에 `PATCH`를 쓰는데, 레퍼런스 §12는 "상태 변경은 POST `/{동사}`, PATCH 없음". `user` 구현 때 맞출 것〕

## 8. 고도화로 미룬 것 [계정·권한 §9, 레퍼런스 §9.6]

첫 로그인 비밀번호 변경 강제, 내 비밀번호 변경, 로그인 5회 실패 잠금, 세션 DB 저장(Spring Session JDBC), 한 사람 여러 역할·케이스 공동 담당, SSO. 전부 지금 설계를 바꾸지 않고 컬럼·테이블을 더하는 식으로 들어갈 수 있어요.
