# dajaba — AML 의심거래 탐지 + STR 초안 (백엔드)

거래 데이터에서 자금세탁 의심 거래를 룰과 AI로 찾아 조사역에게 우선순위대로 보여주고, STR(의심거래보고서) 초안을 AI가 쓴 뒤
코드와 AI가 이중 검증해 승인까지 가는 내부 업무 시스템. Spring Boot 3 / Java 17 / PostgreSQL 16 / Flyway.

설계 문서(정본)는 팀 노션: 백엔드 설계 가이드·레퍼런스, 인터페이스 규약, 계정·권한 안내, 10/27 회의 안건.
코드 규칙·구조·기능별 요약과 리뷰 체크리스트는 [`docs/README.md`](docs/README.md), 흐름 그림은 [`docs/flows/`](docs/flows/README.md).

## 처음 받았을 때 (5분)

```bash
git clone <repo> && cd dajaba
# 1. 환경변수 — 노션 「다자바 환경변수」 페이지의 내용을 .env 로 저장 (아래 변수 이름 참조). .env 는 커밋되지 않는다.
# 2. 기동
docker compose up --build
# 3. 확인
open http://localhost:8080/swagger-ui.html
```

swagger에서 **`POST /api/v1/auth/mock-login?login_id=inv01`** → 200, 그 다음 **`GET /api/v1/auth/me`** → 200 이면 준비 끝.
앱 없이 DB만 띄우고 IDE에서 돌리려면 `docker compose up db` 후 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` (환경변수는 .env 내용을 IDE 실행 설정에).

### .env 변수 이름 (값은 노션)

| 변수 | 쓰는 곳 |
|---|---|
| `POSTGRES_PASSWORD` | db 컨테이너 postgres 슈퍼유저 |
| `APP_MIGRATE_PASSWORD` | Flyway 계정 `app_migrate` (DDL) |
| `APP_BACKEND_PASSWORD` | 앱 계정 `app_backend` (읽기·쓰기) |
| `APP_AI_PASSWORD` | AI 서버 계정 `app_ai` (원천 4개 SELECT + ai 스키마) |
| `DEV_USER_PASSWORD` | local 시연 계정 4명(admin·inv01·apr01·inv02)의 비밀번호 |
| `AI_BASE_URL` | AI 서버 주소. 비우면 `http://ai:8000`. local은 어차피 목업 |
| `AI_INTERNAL_KEY` | AI 서버와 맞추는 내부 키(32자 이상 난수) |

DB를 완전히 초기화하려면 `docker compose down -v` (볼륨 삭제 → 다음 기동 때 `db/init/01_roles.sh` 와 Flyway가 다시 돈다).

## 지금 들어 있는 것 (W1 뼈대, 10/26)

| 묶음 | 파일 | 상태 |
|---|---|---|
| 폴더 구조 | `com.team.aml.{source,detection,cases,str,user,shared}` × `domain/application/infrastructure/api` | 각 폴더 `package-info.java`에 담당·들어갈 클래스 적어 둠 |
| DB | `db/init/01_roles.sh`(역할 3개·스키마 2개), Flyway `V001`~`V004` (권한, 원천 4개+스테이징, users+SYSTEM, batch_runs) | 레퍼런스 3절 그대로. `ddl-auto=validate` |
| shared/domain | `BaseEntity`(감사 4), `CreatedOnlyEntity`, `DomainException`, `NotFoundException`, `DisplayNumber` | |
| shared/api | `GlobalExceptionHandler`(8.2 에러 JSON), `ErrorResponse`, `PageResponse` | |
| shared/security | `SecurityConfig`(세션·CSRF·URL 역할), `CurrentUser`, `SessionAuditorAware`, 401/403 JSON, `request_id` MDC | 진짜 |
| shared/batch | `BatchRun`, `BatchRunRepository`, 409 예외 2개 | 안건 14 반영 |
| shared/ai | `AiClient` 인터페이스, DTO 틀, `MockAiClient`(local/test), 기동 시 health | WebClient 구현은 W3 |
| user | `User`, `Role`, `UserRepository`, `GetMe`, `AuthController(/me)`, `DevUserSeeder`, **`MockLoginController`(local 전용)** | 진짜 login은 W2 |
| 테스트 | `ArchitectureTest`(ArchUnit, DB 없이), `ApplicationContextTest`(Testcontainers, Docker 필요) | |
| 자료 | `src/test/resources/fixtures/ibm_small/`(축소본 — 지금은 합성 샘플), `tools/fixture/`, `docs/flows/` | |

### 로그인이 목업인 범위

`POST /api/v1/auth/mock-login?login_id=…` 만 가짜다 — **비밀번호를 안 본다.** 그 뒤는 전부 진짜: 세션 쿠키 `SESSION`, CSRF(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더),
같은 계정 동시 세션 1개, 30분 만료, `GET /auth/me`, `POST /auth/logout`, 401/403 JSON. local 프로파일에서만 등록되고 prod에는 없다.
W2에 `POST /api/v1/auth/login`(BCrypt 검사 + access_logs)이 들어오면 `MockLoginController`를 지운다. 그때 준혁·지윤·채영 코드는 바뀌지 않는다 — 전부 `CurrentUser`로 사용자를 꺼내기 때문.

프론트: `axios` 에 `withCredentials: true`. 쓰기 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로(axios 기본 동작). swagger에서는 오른쪽 위 Authorize에 쿠키 값을 붙여 넣는다.

## 규칙 세 줄

1. **DB 구조는 Flyway 파일만.** `V{번호}__{설명}.sql`, PR 하나에 파일 하나, 번호는 팀 채팅에 예약. 적용된 파일은 안 고친다.
2. **업무 규칙은 엔티티 안에.** `setStatus()` 없음. 상태 전이는 `case.escalate()` 같은 메서드. `@Transactional`은 application에만.
3. **기능 폴더끼리는 application 유스케이스로만 부른다.** 의존 방향 `source ← detection ← cases ← str`. `ArchitectureTest`가 CI에서 막는다.

자세한 건 백엔드 설계 가이드(먼저 읽기) → 레퍼런스(찾아보기).

## 다음

- W2 진희: 진짜 login, access_logs, `WebClientAiClient`(W3), 규약 v0.2 반영
- W2 준혁: V005 detection, `LoadIbmDataset`, 룰 1개 · 지윤: V006 cases, 상태 전이 + `CaseTest` · 채영: V008 str (W3)
- 축소본: 진희 PC에서 `python tools/fixture/make_fixture.py` 로 실제 IBM 원본에서 다시 생성 → `src/test/resources/fixtures/ibm_small/` 덮어쓰기
