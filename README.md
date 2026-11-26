# 🛡️ 다자바 백엔드 (dajaba-backend)

> 거래 데이터에서 자금세탁 의심 거래를 **룰로 찾고**, AI 점수로 **조사할 순서를 정해** 조사역에게 보여 주고, 의심거래보고서(STR) **초안 작성 → 검증 → 승인 → 확정본 PDF**까지 이어 주는 Spring Boot 서버

![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-V001~V019-CC0200?logo=flyway&logoColor=white)
![Testcontainers](https://img.shields.io/badge/JUnit5-Testcontainers-25A162?logo=junit5&logoColor=white)

<br>

## 1. 👥 백엔드 담당

| 이름 | 맡은 일 |
|---|---|
| 박진희 (팀장) | 공통 코드·로그인·권한·감사 기록·AI 연동(`AiClient`) · 운영 도구 · 설계 문서 · `shared/` `user/` |
| 배준혁 | 원천 적재·룰 탐지·점수 배치 · `source/` `detection/` |
| 김지윤 | 케이스 생성·배정·조사·질문 답변 · `cases/` |
| 오채영 | STR 초안·수정·검증·승인·모의 제출·확정본 PDF · `str/` |

다자바는 다섯 명이 만든 프로젝트예요. 이 리포는 그중 백엔드 서버만 담고 있어요.

| 리포 | 내용 |
|---|---|
| **dajaba-backend** (여기) | Spring Boot — 적재·룰 탐지·케이스·STR 워크플로·승인·확정본 PDF |
| [dajaba-frontend](https://github.com/dajaba-team/dajaba-frontend) | React — 조사역·승인자·관리자 화면 |
| [dajaba-ai](https://github.com/dajaba-team/dajaba-ai) | FastAPI — 점수·조사 순위·STR 초안·검증·참고 사례·질문 답변. 이 서버가 HTTP로 불러요 |

<br>

## 2. 📢 개요

은행 조사역은 하루에도 수많은 경보를 받지만, 다 볼 수는 없어요. 다자바 백엔드는 거래 원천을 적재하고 룰로 경보를 만든 뒤, AI 점수를 받아 **우선순위가 높은 경보만 케이스로** 만들어요. 조사역은 케이스를 조사해 STR 초안을 받고, 고치고, 검증을 통과시킨 뒤 승인자에게 올려요. 승인된 보고서는 **바뀌지 않는 확정본 PDF**로 남아요.

| 문제 | 이 서버가 하는 일 |
|---|---|
| 거래 508만 건에서 의심 패턴을 사람이 찾기 어려움 | 자금세탁 유형 룰 6개(분산 송금·집중 입금·환전 중계·순환·흩었다 모으기·모았다 흩기)로 하루치 경보 생성 |
| 경보가 하루 1,500건이 넘음 | AI 점수 + 운영 정책(CasePolicy)으로 HIGH만 케이스로 열고 담당자 배정 |
| 조사·보고 과정이 사람마다 다름 | 케이스 상태 전이(OPEN → REVIEWING → ESCALATED → DRAFTED → PENDING_APPROVAL → APPROVED → REPORTED)를 엔티티가 강제 |
| AI가 쓴 초안을 그대로 믿기 어려움 | AI 코드 검사 + 평가자 검증을 통과한 현재 본문만 승인 요청 가능, 자기 승인 금지 |
| 보고 뒤 "그때 무엇을 보고했나" 증명 필요 | 승인 시점 스냅샷으로 확정본 PDF 생성 · SHA-256 보관 · 누가 언제 열람했는지 기록 |

<br>

## 3. 🛠 기술 스택

| 영역 | 사용 |
|---|---|
| Backend | ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring%20Security-6DB33F?logo=springsecurity&logoColor=white) ![JPA](https://img.shields.io/badge/Spring%20Data%20JPA-59666C?logo=hibernate&logoColor=white) ![WebClient](https://img.shields.io/badge/WebClient-6DB33F?logo=spring&logoColor=white) ![springdoc](https://img.shields.io/badge/springdoc-85EA2D?logo=swagger&logoColor=black) |
| Data | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white) ![pgvector](https://img.shields.io/badge/pgvector-4169E1?logo=postgresql&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white) |
| Ops | ![RabbitMQ](https://img.shields.io/badge/RabbitMQ-FF6600?logo=rabbitmq&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white) ![MinIO](https://img.shields.io/badge/MinIO-C72E49?logo=minio&logoColor=white) ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?logo=grafana&logoColor=white) ![Docker](https://img.shields.io/badge/Docker%20Compose-2496ED?logo=docker&logoColor=white) |
| PDF | ![openhtmltopdf](https://img.shields.io/badge/openhtmltopdf-B30B00) ![NanumGothic](https://img.shields.io/badge/NanumGothic-03C75A) |
| Test | ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?logo=junit5&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-291A3F) ![ArchUnit](https://img.shields.io/badge/ArchUnit-555555) |

버전은 [`build.gradle.kts`](build.gradle.kts) 기준이에요(Spring Boot 3.3.5 · PostgreSQL 16 + pgvector 0.8.2). Spring Security는 세션 + CSRF, WebClient는 AI 호출, springdoc은 Swagger UI, openhtmltopdf + 나눔고딕은 확정본 PDF, ArchUnit은 폴더 의존 방향 검사에 써요. RabbitMQ는 작업 큐, Redis는 로그인 세션, MinIO는 확정본 PDF 보관이에요. RabbitMQ·Redis·MinIO는 **기본으로 꺼져 있고**, 꺼져 있으면 앱 안 실행기·앱 메모리 세션·로컬 폴더로 똑같이 돌아요. 도구가 죽어도 로그인·케이스·STR은 멈추지 않아요.

<br>

## 4. ✨ 주요 기능

| 기능 | 엔드포인트 (대표) | 한 줄 |
|---|---|---|
| 원천 적재 | `POST /api/v1/admin/batches/load` | IBM 합성 거래 508만 건을 `COPY`로 한 번 적재 |
| 룰 스캔 | `POST /api/v1/admin/batches/rule-scan?date=` | 룰 6개로 그날 경보 생성 |
| 점수 | `POST /api/v1/admin/batches/score?date=` | 경보를 500개씩 AI에 보내 점수 저장, 실패분만 재처리 |
| 케이스 생성·배정 | `POST /api/v1/admin/batches/open-cases?date=` · `POST /api/v1/cases/{case_no}/assign` | 운영 정책으로 등급을 매겨 케이스를 열고 관리자가 배정 |
| 케이스 조사 | `GET /api/v1/cases/{case_no}` · `/transactions` · `/graph` · `/summary` | 근거 거래·자금 흐름 그래프·AI 요약 5줄·메모·이력 |
| 질문 답변 | `POST /api/v1/cases/{case_no}/questions` | 케이스에 대해 묻고 근거 거래가 붙은 답 받기 |
| STR 초안 | `POST /api/v1/cases/{case_no}/str-drafts` | AI 초안 생성(동기 201 · 비동기 202) |
| 수정·검증·승인 요청 | `PUT .../revisions` · `POST .../verify` · `POST .../submit` | 수정본마다 해시, 현재 본문이 검증을 통과해야 승인 요청 |
| 승인·반려 | `POST /api/v1/approvals/{draft_no}/approve` · `/reject` | 담당했던 사람은 승인 불가, 반려는 보완 요청·종료 |
| 모의 제출·확정본 PDF | `POST .../report` · `GET /api/v1/str-drafts/{draft_no}/report.pdf` | 승인 스냅샷으로 PDF 생성·보관, 열람 기록 |
| 관리 | `/api/v1/admin/users` · `/access-logs` · `/ai-calls` · `/batches` | 계정·역할, 열람 기록, AI 호출 기록, 배치 상태 |

모든 API는 세션 쿠키 + CSRF 헤더로 불러요. 역할은 조사역(`INVESTIGATOR`)·승인자(`APPROVER`)·관리자(`ADMIN`) 세 가지예요. 전체 목록과 요청·응답은 실행 뒤 Swagger(`/swagger-ui.html`)에서 볼 수 있어요.

### 4.1 원천 적재·룰 탐지·점수 (준혁)

> IBM 파일 3개 → `ops.transactions`(508만 건) → 룰 6개 스캔 → 경보 + 근거 거래 → AI 점수(500개씩) → 경보마다 점수·입력 품질 저장

- 룰은 "새 관련 거래가 생긴 분"마다 지금 창과 1분 전 창을 비교해 **거짓 → 참**일 때만 경보를 내요(R04 순환은 고리가 닫힐 때마다). 창은 96시간이에요.
- 금액은 방향대로(입금은 받은 금액, 출금은 보낸 금액) 다루고, 다른 통화끼리 더하지 않아요. 환산은 R03 환전 중계 하나만 써요.
- 배치는 룰 스캔 → 점수 → 케이스 생성 순으로 성공하면 다음 단계가 이어져요. 실패한 묶음만 다시 보내요.
- 룰 정의는 [`docs/06_features/rules.md`](docs/06_features/rules.md), 흐름은 [`docs/06_features/source_detection.md`](docs/06_features/source_detection.md)에 있어요.

### 4.2 케이스 (지윤)

> SCORED 경보 → CasePolicy가 점수로 HIGH·MEDIUM·LOW → HIGH를 케이스로 → 관리자 배정 → 조사역 조사 → 상신(ESCALATED) 또는 종료

- 상태 전이는 `Case` 엔티티 메서드로만 바꿔요(`setStatus()` 없음). 동시 수정은 `version`으로 막아요(409 `CONCURRENT_UPDATE`).
- 조사역은 새 케이스를 스스로 맡을 수 없고, 관리자가 배정·변경해요.
- 근거 거래 보기, 자금 흐름 그래프, AI 요약, 메모 이력, 질문 답변이 한 화면에 모여요.

### 4.3 STR 워크플로·확정본 PDF (채영)

> 초안 생성(AI) → 조사역 수정(수정본마다 SHA-256) → 검증(AI) → 승인 요청 → 승인·반려 → 모의 제출 → 확정본 PDF

- **현재 본문만 통과**: 승인 요청은 지금 본문 해시와 검증한 본문 해시가 같고 검증을 통과했을 때만 받아요.
- **자기 승인 금지**: 그 케이스를 담당했던 사람은 승인·반려할 수 없어요. 모의 제출과 PDF 다시 만들기는 그 초안을 승인한 사람만 할 수 있어요.
- **확정본은 바뀌지 않음**: 승인 시점의 본문·거래·서식 데이터를 스냅샷으로 고정해 별지 제1호 서식 PDF를 만들어요. 파일은 SHA-256과 함께 보관하고, 내려받을 때 해시가 다르면 내보내지 않아요.
- PDF는 커밋 뒤 RabbitMQ 큐(꺼지면 앱 안 실행기)에서 만들어요. 실패하면 승인한 사람이 다시 만들 수 있어요.
- 화면의 버튼은 서버가 주는 `available_actions`만 보고 켜요. 버튼 조건과 API의 403·409 검사는 같은 코드로 판단해요.

### 4.4 공통·보안·감사 (진희)

> 로그인(세션 + CSRF) → 역할별 URL 권한 → 유스케이스의 담당자·상태 검사 → 열람 기록·AI 호출 기록

- 세션은 같은 계정 1개, 30분 만료예요. 로그인 실패는 이유와 상관없이 같은 401을 줘요.
- 에러는 하나의 JSON 형식(`error.code`·`message`·`details`)과 `request_id`로 내려가요.
- STR 본문·PDF 열람, 내보내기는 `access_logs`에, AI 호출은 시도마다 `ai_call_logs`에 메타데이터만 남겨요. 로그에는 계좌번호·금액·본문을 남기지 않아요.

<br>

## 5. 📌 아키텍처

<!-- 📷 사진 첨부: 시스템 아키텍처 (AI 리포와 같은 그림을 써도 됨) -->
<img width="4640" height="3020" alt="image" src="https://github.com/user-attachments/assets/6ec0da5a-3b9c-416e-975b-f07bfda61ffd" />


> 로컬은 같은 구성을 Docker Compose로 띄워요. `name: dajaba`로 프로젝트 이름을 고정해서, AI 서버는 `dajaba_default` 네트워크에 붙어요.

- 프론트는 백엔드만 불러요. AI 서버는 백엔드만 불러요(`X-Internal-Key`).
- 백엔드와 AI는 같은 PostgreSQL을 써요. 백엔드는 `ops` 스키마를 읽고 쓰고, AI는 원천 4개 테이블만 읽고 `ai` 스키마에 써요.
- 기능 폴더 6개(`source`·`detection`·`cases`·`str`·`user`·`shared`)는 각각 `api → application → domain`, `infrastructure` 4계층이에요. 폴더끼리는 `application` 유스케이스로만 부르고, 방향은 `source ← detection ← cases ← str`이에요. `ArchitectureTest`가 CI에서 막아요.

| 백엔드 → AI | 쓰는 곳 |
|---|---|
| `POST /v1/scores` | 점수 배치 |
| `GET /v1/dl-rankings/{scan_date}` | ML+DL 조사 순위 |
| `POST /v1/str/drafts` · `/v1/str/verify` | STR 초안·검증 |
| `POST /v1/cases/summary` · `/v1/cases/ask` | 케이스 요약·질문 답변 |
| `POST /v1/references/search` · `/v1/references/str` | 참고 사례 찾기·승인 STR 색인 |

AI 서버 없이 화면을 개발할 수 있게 `AML_AI_MOCK=true`(기본)면 목업 응답을 써요.

<br>

## 6. 🗂 ERD (`ops` 스키마)

<!-- 📷 사진 첨부: ops 스키마 ERD -->
<img width="2850" height="5693" alt="image" src="https://github.com/user-attachments/assets/d5157274-394a-4156-a9f4-2e2278b3127f" />


Flyway `V001`~`V019`, 테이블 23개예요. DB 구조는 Flyway 파일로만 바꾸고(`ddl-auto=validate`), 이미 적용한 파일은 고치지 않아요.

| 묶음 | 테이블 |
|---|---|
| 원천 | `transactions` · `accounts` · `entities` · `customer_attributes` · `source_files` |
| 탐지 | `rules` · `alerts` · `alert_transactions` · `alert_scores` · `batch_runs` |
| 케이스 | `cases` · `case_history` · `case_summaries` · `case_questions` |
| STR | `str_draft_requests` · `str_drafts` · `str_revisions` · `verification_results` · `approvals` · `str_reports` |
| 계정·기록 | `users` · `access_logs` · `ai_call_logs` |

번호 규칙과 쓴 번호는 [`docs/05_flyway.md`](docs/05_flyway.md)에 있어요.

<br>

## 7. 📊 데이터

| 데이터 | 내용 |
|---|---|
| 거래 | Kaggle 「IBM Transactions for Anti Money Laundering」 **HI-Small** (거래 508만 건). 실제 은행 데이터는 쓰지 않았어요 |
| 고객 속성 | 적재 단계에서 만든 합성 값(`customer_attributes`) — 보고서에는 늘 "합성"으로 표시 |
| 시연 날짜 | `2022-09-07` 하루 — 경보 1,545 → HIGH 27 → 케이스 27 (rc2 실제 모델 기준) |
| 테스트 축소본 | `src/test/resources/fixtures/ibm_small/` (테스트 전용) |

원본 파일은 커밋하지 않아요. 리포 루트 `data/`에 `HI-Small_Trans.csv`·`HI-Small_accounts.csv`·`HI-Small_Patterns.txt`를 그대로 두면 돼요.

<br>

## 8. 🔀 흐름도

<!-- 📷 사진 첨부: 아래 그림들 (docs/flows/ 의 Mermaid를 이미지로 내보내도 됨) -->

### 배치 — 적재부터 케이스까지
<img width="2850" height="1500" alt="image" src="https://github.com/user-attachments/assets/28e15ac0-9598-4b90-bd8d-5dab785b0d93" />


### 케아스 —접수부터 종결까지
<img width="2850" height="1590" alt="image" src="https://github.com/user-attachments/assets/3869b57e-091a-4319-bbe4-a15df4dbd6ff" />


### STR — 초안부터 확정본 PDF까지
<img width="2850" height="1620" alt="image" src="https://github.com/user-attachments/assets/c4d53bbb-b878-41c7-8db4-115da6df156c" />


### 로그인·권한
<img width="2850" height="1620" alt="image" src="https://github.com/user-attachments/assets/a3bf8f19-5159-45cf-bde7-e31a02a08916" />


글로 된 흐름(Mermaid)은 [`docs/flows/`](docs/flows/README.md)에 있어요 — 배치 · 조사 · STR · 실패 경로 · 로그인 · 경보 일생.

<br>

## 9. 📁 폴더 구조

```
dajaba-backend/
├─ src/main/java/com/team/aml/
│  ├─ source/       준혁 — 원천 적재
│  ├─ detection/    준혁 — 룰 스캔·경보·점수 배치
│  ├─ cases/        지윤 — 케이스·배정·조사·질문 답변
│  ├─ str/          채영 — 초안·수정·검증·승인·모의 제출·확정본 PDF
│  ├─ user/         진희 — 계정·로그인·역할
│  └─ shared/       진희 — 에러 응답·보안·감사 기록·AiClient·배치·작업 큐·지표
│     (기능 폴더마다 api / application / domain / infrastructure)
├─ src/main/resources/
│  ├─ db/migration/ Flyway V001~V019
│  ├─ fonts/        NanumGothic (확정본 PDF)
│  └─ application.yml
├─ db/init/         DB 첫 기동 — pgvector 확장·역할 3개·스키마 2개
├─ contracts/       AI 규약 예시 JSON · MANIFEST
├─ ops/             Prometheus·Grafana·Loki·Alloy 설정
├─ tools/           축소본 만들기 등 도구
├─ docs/            규칙·구조·기능별 설계·결정 기록·흐름
└─ compose.yaml     db·app (+ ops 프로필 도구)
```

<br>

## 10. 🧪 테스트

| 구분 | 무엇을 | 방법 |
|---|---|---|
| 단위 | 엔티티 상태 전이·룰 판정·해시·서식 조립 | `./gradlew test` (DB 없이) |
| 구조 | 폴더 의존 방향·`@Transactional` 위치·compose 규칙 | `ArchitectureTest` · `ComposeStructureTest` |
| DB | 저장소·유스케이스·권한·동시 수정·배치 | `*DbTest` (Testcontainers PostgreSQL, Docker 필요) |
| JSON 계약 | 요청·응답 칸 이름(SNAKE_CASE)·AI 규약 예시 | `@JsonTest` · `contracts/` |
| 시연 리허설 | 빈 DB → 적재 → 룰 → 점수 → 케이스 → STR → 승인 → PDF | 11/24 리허설 순서 (아래 「실행 방법」) |

<!-- ✏️ 채우기: 최신 ./gradlew test 결과 (통과·실패·skip 수와 날짜) -->
| 항목 | 결과 |
|---|---|
| 자동 테스트 (MM/DD) | 통과 ___ · 실패 0 · skip ___ |
| 9/7 시연 데이터 | 경보 1,545 → 점수 실패 0 → HIGH 27 → 케이스 27 |

<br>

## 11. 🚀 실행 방법

준비물: Docker(메모리 8GB 이상 권장), JDK 17(IDE로 띄울 때).

```bash
git clone https://github.com/dajaba-team/dajaba-backend.git
cd dajaba-backend
# .env — 팀 노션 「다자바 환경변수」의 백엔드 부분을 저장 (커밋하지 않음)
docker compose up -d --build          # db + app
open http://localhost:8080/swagger-ui.html
```

- Swagger에서 `POST /api/v1/auth/login` `{"login_id": "admin", "password": "<DEV_USER_PASSWORD>"}` → `GET /api/v1/auth/me`가 200이면 준비 끝이에요. 개발 계정은 `admin`·`inv01`·`inv02`·`apr01`이에요.
- 기본은 AI 목업이에요. 진짜 AI 서버로 띄우려면 [dajaba-ai](https://github.com/dajaba-team/dajaba-ai)를 띄우고 `AML_AI_MOCK=false docker compose up -d app`.
- 운영 도구(RabbitMQ·Redis·MinIO·Prometheus·Grafana·Loki·Elasticsearch·Langfuse)는 `docker compose --profile ops up -d`로 같이 띄워요.
- 시연 데이터는 `admin`으로 `/admin/batches`에서 **원천 적재 → 룰 스캔 → 점수 → 케이스 생성**(날짜 `2022-09-07`) 순서로 만든 뒤 담당자를 배정해요.

<!-- ✏️ 지금 README의 상세 내용(.env 변수 표, 진짜 AI로 띄우기, 시연 데이터 순서, 운영 도구 표, compose.override.yml, Redis·RabbitMQ 확인, 로그인 방법)을
     docs/09_local_setup.md 로 옮기고 아래 링크를 살려 주세요 -->
`.env` 변수 전체, 운영 도구 화면 열기, 시연 데이터 만들기, 문제 해결은 [`docs/09_local_setup.md`](docs/09_local_setup.md)에 있어요.

테스트:

```bash
./gradlew test                         # Docker가 있으면 DB 테스트까지
./gradlew test --tests "*ArchitectureTest"
```

<br>

## 12. 📚 주요 문서

| 문서 | 내용 |
|---|---|
| [`docs/README.md`](docs/README.md) | 문서 지도 (읽는 순서·누가 고치나) |
| [`docs/01_code_rules.md`](docs/01_code_rules.md) | 코드 규칙·이름·JSON·로그·보안·PR 규칙 |
| [`docs/02_structure.md`](docs/02_structure.md) | 기능 폴더 6개 × 4계층, 의존 방향 |
| [`docs/03_shared.md`](docs/03_shared.md) | 공통 코드 — 에러 응답·`CurrentUser`·`AiClient`·배치·작업 큐·관리 포트 |
| [`docs/04_auth_user.md`](docs/04_auth_user.md) | 계정·역할·로그인·CSRF·권한 표 |
| [`docs/05_flyway.md`](docs/05_flyway.md) | 마이그레이션 규칙·번호 표 |
| [`docs/06_features/source_detection.md`](docs/06_features/source_detection.md) | 적재·탐지·점수 |
| [`docs/06_features/rules.md`](docs/06_features/rules.md) | 룰 6개 정의 |
| [`docs/06_features/cases.md`](docs/06_features/cases.md) | 케이스 |
| [`docs/06_features/str.md`](docs/06_features/str.md) | STR·승인·확정본 PDF |
| [`docs/07_review_checklist.md`](docs/07_review_checklist.md) | PR 리뷰 체크리스트 |
| [`docs/08_decisions.md`](docs/08_decisions.md) | 결정 기록·미결 사항 |
| [`docs/flows/`](docs/flows/README.md) | 흐름 그림 (Mermaid) |
