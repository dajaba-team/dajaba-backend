# dajaba — AML 의심거래 탐지 + STR 초안 (백엔드)

거래 데이터에서 자금세탁 의심 거래를 룰과 AI로 찾아 조사역에게 우선순위대로 보여주고, STR(의심거래보고서) 초안을 AI가 쓴 뒤
코드와 AI가 이중 검증해 승인까지 가는 내부 업무 시스템. Spring Boot 3 / Java 17 / PostgreSQL 16 / Flyway.

설계 문서(정본)는 팀 노션: 백엔드 설계 가이드·레퍼런스, 인터페이스 규약, 계정·권한 안내, 10/27 회의 안건.
코드 규칙·구조·기능별 요약과 리뷰 체크리스트는 [`docs/README.md`](docs/README.md), 흐름 그림은 [`docs/flows/`](docs/flows/README.md).

## 처음 받았을 때 (5분)

```bash
git clone <repo> && cd <받은 폴더>   # 폴더 이름은 무엇이든 됨 — compose.yaml 의 name: dajaba 로 프로젝트 이름 고정
# 1. 환경변수 — 노션 「다자바 환경변수」 페이지의 내용을 .env 로 저장 (아래 변수 이름 참조). .env 는 커밋되지 않는다.
# 2. 기동
docker compose up --build
# 3. 확인
open http://localhost:8080/swagger-ui.html
```

swagger에서 **`POST /api/v1/auth/login`** 본문 `{"login_id": "inv01", "password": "<DEV_USER_PASSWORD 값>"}` → 200, 그 다음 **`GET /api/v1/auth/me`** → 200 이면 준비 끝.
앱 없이 DB만 띄우고 IDE에서 돌리려면 `docker compose up db` 후 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` (환경변수는 .env 내용을 IDE 실행 설정에).

### .env 변수 이름 (값은 노션)

`application.yml`·`compose.yaml`이 읽는 변수 전부예요. 기본값이 있는 건 `.env`에 없어도 돼요. 계정·권한은 [`docs/03_shared.md` §11](docs/03_shared.md).

| 변수 | 무엇 | 계정 | AI `.env`와 같은 값 | 기본값 (없으면) |
|---|---|---|---|---|
| `POSTGRES_PASSWORD` | db 컨테이너 슈퍼유저 비밀번호 (볼륨이 빌 때만 씀) | `postgres` | — | 없음 (필수) |
| `APP_MIGRATE_PASSWORD` | Flyway DDL 계정. db 계정 만들기(`01_roles.sh`)와 앱 Flyway가 같이 씀 | `app_migrate` | — | 없음 (필수) |
| `APP_BACKEND_PASSWORD` | 앱 DB 계정 (`ops` 읽기·쓰기) | `app_backend` | — | 없음 (필수) |
| `APP_AI_PASSWORD` | AI 서버 DB 계정. 앱은 안 씀(db 계정 만들기용) | `app_ai` | **같음** | 없음 (필수) |
| `AI_INTERNAL_KEY` | AI 서버와 맞추는 내부 키(32자 이상 난수, `X-Internal-Key`) | — | **같음** | 비어 있음 (`AML_AI_MOCK=true`면 무관) |
| `AI_BASE_URL` | AI 서버 주소 | — | — | compose 앱 `http://ai:8000` · IDE `http://localhost:8000` |
| `AML_AI_MOCK` | local에서 AI 목업(`true`)·진짜 AI 서버(`false`). `true`/`false`만 — 다른 값이면 앱이 안 떠요 | — | — | `true` (목업) |
| `AML_AI_ASK_ENABLED` | 케이스 질문 답변 켜기(`true`)·끄기(`false`). 참·거짓으로 못 읽는 값(예: `maybe`)이면 앱이 안 떠요 | — | — | `false` (꺼짐) |
| `AML_AI_TIMEOUT_SECONDS`·`AML_AI_MAX_BATCH` | AI 호출 한 번의 전체 시간 한도(초, 재시도 포함)·점수 묶음 크기. 보통 안 바꿔요. 빈 값·0 이하이거나 묶음이 500을 넘으면(규약 6.2) 앱이 안 떠요 | — | — | `90`·`500` |
| `DEV_USER_PASSWORD` | local 시연 계정 4명(admin·inv01·apr01·inv02)의 비밀번호 | — | — | 비어 있음 → 개발 계정을 안 만들고 경고 한 줄 |
| `DB_HOST` | DB 호스트 | — | — | compose 앱 `db`(고정) · IDE `localhost` |
| `DB_PORT` | DB 포트 | — | — | `5432` |
| `DB_NAME` | DB 이름 | — | — | `aml` |
| `AML_DATASET_DIR` | 적재할 IBM 파일 폴더 | — | — | compose 앱 `/data`(고정, `./data` 를 붙임) · IDE `./data` |
| `AML_CASE_POLICY_VERSION` | 케이스 등급 경계값 버전 | — | — | 기본값 없음, 미결 23에서 확정 — 비면 케이스 생성 배치 `POLICY_NOT_CONFIGURED` |
| `AML_CASE_POLICY_LOW_TO_MEDIUM` | 점수 경계 LOW → MEDIUM | — | — | 위와 같음 |
| `AML_CASE_POLICY_MEDIUM_TO_HIGH` | 점수 경계 MEDIUM → HIGH | — | — | 위와 같음 |
| `AML_BATCH_CHAIN_ENABLED` | 배치 자동 연쇄 켜기(`true`)·끄기(`false`) — 룰 스캔 성공 → 점수 → 케이스 생성. `AML_RABBITMQ_ENABLED=true`도 있어야 이어져요. 끄면 단계마다 직접 실행 (SCRUM-349) | — | — | `false` |
| `AML_RABBITMQ_ENABLED` | RabbitMQ 작업 큐 켜기(`true`)·끄기(`false`). 끄면 케이스 생성 등은 앱 안 실행기로 — 지금과 같아요 (SCRUM-276) | — | — | `false` |
| `RABBITMQ_HOST` · `RABBITMQ_PORT` | RabbitMQ 주소 | — | — | compose 앱 `rabbitmq`(고정) · IDE `localhost`·`5672` |
| `AML_SESSION_REDIS_ENABLED` | 로그인 세션을 Redis(db 0)에(`true`)·앱 메모리에(`false`). **`true`/`false`만** — 다른 값(오타 `ture` 등)이면 두 설정이 다 안 걸려 `SessionRegistry` 빈이 없다는 기동 실패가 나요. 끄면 지금과 같아요 — 앱을 다시 띄우면 모두 로그아웃 (SCRUM-273) | — | — | `false` |
| `REDIS_HOST` · `REDIS_PORT` | Redis 주소(세션을 켰을 때만 씀) | — | — | compose 앱 `redis`(고정) · IDE `localhost`·`6379` |
| 참고: `SPRING_PROFILES_ACTIVE` | 프로파일 | — | — | compose 앱은 `local` 고정 · IDE는 실행 설정에 `local` |

IDE에서 띄우면 앱이 리포 루트의 `.env`를 직접 읽어요(`application.yml`의 `spring.config.import`). compose로 띄운 앱은 위 표의 변수를 `compose.yaml`이 넘겨 줘요.

**진짜 AI로 띄우기** (기본은 목업 — 토큰·AI 서버 없이 화면 개발. 연결 확인·통합 테스트·시연만 진짜 AI, SCRUM-262)

```bash
AML_AI_MOCK=false docker compose up -d app   # 진짜 AI — 같은 aml-app 컨테이너를 다시 만들어요
docker compose up -d app                     # 목업으로 돌아가기
```

- 명령 앞에 붙여 쓰고 `export` 하지 마세요. 셸에 남아 있으면 `./gradlew test`까지 진짜 AI로 떠서 테스트가 깨질 수 있어요.
- 시연처럼 계속 진짜 AI로 둘 때는 `.env`에 `AML_AI_MOCK=false`. 이러면 compose·IDE 둘 다 **기본이 진짜 모드**가 돼요 — 목업으로 돌아가려면 그 줄을 지우거나 `false`를 `true`로.
- AI 서버(dajaba-AI compose)가 떠 있어야 하고, `AI_INTERNAL_KEY`가 AI `.env`와 같아야 해요.

DB를 완전히 초기화하려면 `docker compose down -v` (볼륨 삭제 → 다음 기동 때 `db/init/00_extensions.sql`·`01_roles.sh` 와 Flyway가 다시 돈다).

### 시연 데이터 만들기 — 빈 DB에서 케이스까지 (리허설 순서, SCRUM-250)

위 3단계까지는 **로그인만** 돼요. 목록에 케이스가 보이려면 데이터를 넣고 배치 네 개를 순서대로 돌린 뒤, 관리자가 조사역에게 배정해야 해요.

1. **원본 파일 3개를 `./data`에** — Kaggle 「IBM Transactions for Anti Money Laundering (AML)」의 HI-Small 세 파일 `HI-Small_Trans.csv`·`HI-Small_accounts.csv`·`HI-Small_Patterns.txt`를 리포 루트 `data/`에 그대로 둬요(파일 이름 바꾸지 않음, 커밋 안 됨). compose 앱은 `./data`를 `/data`로 읽기 전용으로 붙이고, IDE는 `AML_DATASET_DIR` 기본 `./data`.
   - `src/test/resources/fixtures/ibm_small/`(축소본)은 **테스트 전용**이에요. 계좌 파일이 없고, 적재는 HI-Small 전체 건수(508만)로 검사해서 축소본을 넣으면 `DATASET_FILES_MISSING` 또는 "검증값 불일치"로 실패해요.
2. **`.env`에 케이스 등급 경계값 세 줄** — `AML_CASE_POLICY_VERSION`·`AML_CASE_POLICY_LOW_TO_MEDIUM`·`AML_CASE_POLICY_MEDIUM_TO_HIGH`(값은 노션 `.env`). 없으면 4단계가 `POLICY_NOT_CONFIGURED`로 거절돼요. rc2 실제 모델로 바꾸는 날 값이 바뀌어요(SCRUM-329).
3. **`admin`으로 로그인해 배치 네 개를 순서대로 돌리고 담당을 배정** — 프론트 `/admin/batches` 화면 버튼으로 하거나, swagger로 아래를 차례로 불러요. 날짜는 **`2022-09-07` 하나만** 써요(경계값을 9/7 기준으로 골라서, 다른 날은 HIGH가 하루 30건을 넘을 수 있어요 — 11/20 #개발).

   | 순서 | 호출 | 응답 | 다음으로 넘어가는 때 |
   |---|---|---|---|
   | ① 적재 | `POST /api/v1/admin/batches/load` | 202 (뒤에서 돌아요) | `GET /api/v1/admin/batches` 맨 위 `LOAD_DATASET`이 `SUCCEEDED`. 수 분 걸려요. **한 번만** — 다시 하려면 `docker compose down -v` |
   | ② 룰 스캔 | `POST /api/v1/admin/batches/rule-scan?date=2022-09-07` | 200 (끝날 때까지 기다림) | 경보 건수가 응답에 |
   | ③ 점수 | `POST /api/v1/admin/batches/score?date=2022-09-07` | 200 — **일부 실패가 있어도 200** | 응답의 `scored`·`failed`. 다시 부르면 실패분만 재처리 |
   | ④ 케이스 생성 | `POST /api/v1/admin/batches/open-cases?date=2022-09-07` | 202 | 배치 목록 맨 위 `OPEN_CASES`가 `SUCCEEDED` |
   | ⑤ 담당 배정 | 프론트 케이스 상세 [담당자 배정] 또는 `POST /api/v1/cases/{case_no}/assign` `{"assignee": "inv01", "version": …}` — **`admin`만** | 200 | 그 뒤 `inv01`로 `/cases` → 「내 담당」에 보임. 조사역은 새 케이스를 스스로 맡을 수 없어요(SCRUM-339) |

   - ③은 기본(`AML_AI_MOCK=true`)이면 **목업 점수**(경보마다 0.05~0.95 고르게)라 HIGH 수가 실제와 달라요. 실제 점수는 dajaba-AI를 띄우고 `AML_AI_MOCK=false`(위 「진짜 AI로 띄우기」).
   - 실제 모델로 처음 채점할 때는 **rc2로 바꾼 뒤** ③을 처음 돌려요. rc1으로 먼저 돌리면 R03·R05·R06 경보가 `RULE_NOT_SUPPORTED`로 남고 다시 보내지 않아요(#155).
   - 숫자 확인(11/21 rc2 실제 모델 기준): 9/7 경보 1,545 → 점수 실패 0 → HIGH 27 → 케이스 27.

### 프로젝트 이름 `dajaba` · pgvector (11/8, SCRUM-124)

- `compose.yaml` 맨 위 `name: dajaba` — 폴더 이름과 상관없이 네트워크 `dajaba_default`(AI compose가 붙는 곳), 볼륨 `dajaba_aml-db-data`.
- **다른 폴더 이름으로 띄워 둔 적이 있으면** 한 번만: `docker rm -f aml-db aml-app` 뒤 `docker compose up`. 새 빈 볼륨이라 **적재를 다시** 해요(옛 볼륨은 지워지지 않고 남아요).
- db 이미지는 `pgvector/pgvector:0.8.2-pg16-trixie`(PostgreSQL 16 그대로 + pgvector 0.8.2, 원래 `postgres:16`과 같은 trixie — collation 버전이 같아요). 새 볼륨은 `db/init/00_extensions.sql`이 확장을 만들어요. **이미 있는 볼륨**은 손으로 1회:

```bash
docker exec aml-db psql -U postgres -d aml -c "CREATE EXTENSION IF NOT EXISTS vector;"
docker exec aml-db psql -U postgres -d aml -c "\dx"     # vector 가 보이면 끝
```

## 운영 도구 (ops 프로필, 11/18, SCRUM-263)

지표·로그·세션·큐·파일·검색·LLM 추적 도구를 `compose.yaml`에 **`profiles: [ops]`** 로 넣었어요. 지금은 **띄우기까지만** 돼요. 앱·AI에서 도구를 부르는 연결은 각자 Jira에서 해요(노션 「운영 도구 붙이기 — 팀원별 담당」).

```bash
docker compose up -d                  # 평소 — db·app만 (지금과 같음, 도구 없음)
docker compose --profile ops up -d    # 도구까지
docker compose --profile ops down     # 도구까지 내리기 (볼륨은 남음)
```

- 시연처럼 늘 도구까지 띄울 때는 `.env`에 `COMPOSE_PROFILES=ops`를 넣어요.
- **Docker Desktop 메모리 최소 8GB · 권장 10GB**(도구 상한 합계 약 6.8GB + db·app·ai).
- 도구는 **호스트 포트를 열지 않아요.** app·ai의 `depends_on`에도 없어서 도구가 죽어도 로그인·케이스·STR은 그대로 돌아요. AI 컨테이너는 `dajaba_default` 네트워크에서 `langfuse-web:3000`·`elasticsearch:9200`처럼 이름으로 찾아요.
- 비밀값이 하나라도 비어 있으면 `ops-env-check`가 **빈 변수 이름만** 찍고 멈춰요(값은 찍지 않아요). 그러면 비밀값을 쓰는 도구는 뜨지 않아요(비밀값이 없는 Prometheus·Loki·Alloy는 그대로 떠요). 프로필 없이 `docker compose up`을 하면 이 검사는 돌지 않아요.

| 도구 | 무엇 | 첫 사용처 · 붙이는 사람 | 메모리 상한 |
|---|---|---|---|
| prometheus | 지표 수집. 대상은 `app:8081/actuator/prometheus`(관리 포트, 호스트엔 안 열림 — `docs/03_shared.md` §13)·`ai:8000/metrics`(AI 끝점이 생기기 전에는 DOWN) | 백엔드·AI 공통 지표 — 진희, 기능 지표 — 각자 | 256m |
| grafana | 지표·로그 화면. Prometheus·Loki 데이터 소스 자동 등록, 익명 접근 끔 | 대시보드 — 각자 | 384m |
| loki · alloy | 컨테이너 표준출력 로그 모음(`dajaba_default` 네트워크 컨테이너만). Alloy는 Docker 소켓을 **읽기 전용**으로 붙여요 — 컨테이너 목록·로그를 읽는 공식 방법이 소켓뿐이라서, 그래서 ops 프로필에만 둬요 | 로그 형식 — 각자 | 256m · 256m |
| redis | **db 0 = 로그인 세션, db 1 = Langfuse**. 앱은 `AML_SESSION_REDIS_ENABLED=true`일 때만 세션을 여기 둬요(기본 꺼짐 = 앱 메모리, 키 `aml:session:*`). 켠 채 Redis가 죽으면 앱은 그대로 떠 있고, 로그인·로그인한 요청만 503 `SESSION_STORE_UNAVAILABLE`이에요(쿠키 없는 요청은 지금처럼 401). Langfuse 필수인 `noeviction`이 db 0에도 걸려서, 메모리가 차면 지우는 대신 **세션 쓰기까지 실패**(로그인 안 됨)해요. 그래서 컨테이너 상한 256m 안에서 `maxmemory 200mb`로 두고(강제 종료 대신 쓰기 거부), Grafana에서 Redis 메모리를 봐요 | 세션 — 지윤(SCRUM-273) | 256m |
| rabbitmq | vhost `aml`, 관리 UI 포함. 큐 이름은 `batch.*`·`case.*`·`str.pdf.*`, **메시지에는 작업 ID만**(`batch_run_id`·`draft_id`). 앱 쪽 공통 설정은 `shared/messaging` — 큐는 `JobQueues`에 한 줄 추가하면 DLQ까지 앱이 선언해요. 앱은 `AML_RABBITMQ_ENABLED=true`일 때만 붙어요(기본 꺼짐). 붙이는 법은 [`docs/03_shared.md` §14](docs/03_shared.md) | 배치 잇기 — 준혁, 케이스 생성 — 지윤(`case.open-cases`, SCRUM-276), 확정본 PDF — 채영 | 384m |
| minio (+ minio-init) | 파일 저장. 버킷 `str-reports`(확정본 PDF)·`langfuse`(Langfuse 이벤트)를 `minio-init`이 한 번 만들어요. 공식 Docker Hub 이미지가 중단돼서 Chainguard 이미지를 **digest로 고정**했어요(무료판은 `latest` 태그만 있음) | 확정본 PDF — 채영 | 256m |
| elasticsearch | 단일 노드, 보안 켬(`elastic` 계정), 힙 512m | RAG 키워드 검색 — 진희 | 1g |
| langfuse-web · langfuse-worker · clickhouse | LLM 호출 추적(v4, 직접 띄우기). Postgres는 db의 `langfuse` DB(`langfuse-db-init`이 없을 때만 만들고, 비밀번호는 기동마다 `.env` 값으로 맞춰요), Redis db 1, MinIO `langfuse` 버킷 | AI LLM 호출 — 진희 | 1g · 768m · 2g (web·worker는 Node 힙 = 상한, 공식 권장) |

**RabbitMQ 켜고 띄우기** (SCRUM-276) — `.env`에 `AML_RABBITMQ_ENABLED=true`를 넣고 도구까지 띄워요.

```bash
docker compose --profile ops up -d rabbitmq      # 브로커 먼저 (healthy 까지 1분쯤)
docker compose up -d --build app                 # 앱이 붙으면서 aml.jobs 교환기·큐·DLQ를 선언
```

관리 UI(`compose.override.yml`로 15672를 열었을 때) `http://localhost:15672` → vhost `aml` → Queues에 `case.open-cases`·`case.open-cases.dlq`·`str.pdf.generate`·`str.pdf.generate.dlq`가 보이면 붙은 거예요. 브로커가 죽어 있거나 꺼져 있으면 앱은 그냥 지금처럼(앱 안 실행기) 돌아요.

**Redis 세션 켜고 띄우기** (SCRUM-273) — `.env`에 `AML_SESSION_REDIS_ENABLED=true`를 넣고 Redis까지 띄워요.

```bash
docker compose --profile ops up -d redis         # ops-env-check 가 비밀값을 본 뒤 뜸
docker compose up -d --build app
```

로그인한 뒤 `docker compose restart app`을 해도 같은 브라우저의 `/api/v1/auth/me`가 200이면 세션 유지가 확인된 거예요. 끄면(`false`) 앱은 Redis에 연결하지 않아요.

**적용 확인** — `PING`이나 health `UP`만으로는 세션 연동을 확인할 수 없어요. 로그인 전후 DB 0의 세션 키 개수와 같은 쿠키의 응답을 함께 봐요. 키 원문(세션 ID)은 출력·공유하지 않아요.

```bash
# Redis 서버 응답 확인 (인증은 컨테이너 REDISCLI_AUTH 사용)
docker compose --profile ops exec -T redis redis-cli PING
# 세션 키는 원문 대신 개수만 확인
docker compose --profile ops exec -T redis redis-cli -n 0 --scan --pattern 'aml:session:sessions:*' | wc -l
# 로그인한 브라우저를 유지한 채 앱만 재시작 → /api/v1/auth/me 200
docker compose restart app
```

로컬 장애 확인은 `docker compose --profile ops stop redis` 뒤 **로그인한 쿠키로** `/api/v1/auth/me`를 호출해 503 `SESSION_STORE_UNAVAILABLE`을 확인하고, 반드시 `docker compose --profile ops start redis`로 복구해요. 세션이 만료되지 않았다면 같은 쿠키로 다시 200이어야 해요. 로그아웃 후에는 401이에요. Redis 장애 중 쿠키 없는 요청은 401일 수 있어요.

**자동 테스트** — Docker를 켜고 아래를 실행해요. 테스트 전용 PostgreSQL·Redis 컨테이너에서 DB 0 저장, 앱 컨텍스트 재생성 후 세션 유지, 중복 로그인·로그아웃, Redis 장애·메모리 부족 503을 확인해요.

```bash
./gradlew test --tests '*RedisSession*Test' --tests '*MemorySessionDefaultTest' --tests '*SessionStore*Test'
./gradlew test
```

`AML_SESSION_REDIS_ENABLED=false`는 메모리 모드로의 명시적 전환이에요. `true`인 상태에서 Redis가 죽는다고 메모리로 자동 전환하지 않아요.

**로그 주의** — Alloy는 앱 로그를 **그대로** Loki로 옮겨요. 로그에 계좌번호·금액·본문·프롬프트 전문을 남기지 않는 규칙(NFR-COM-04)은 앱 쪽 로그 형식에서 지켜요.

**화면을 로컬에서 열기** — 리포 루트에 `compose.override.yml`을 만들어요(커밋하지 않아요, `.gitignore`에 있음). `127.0.0.1`에만 묶어요.

```yaml
services:
  grafana:       { ports: ["127.0.0.1:3001:3000"] }
  langfuse-web:  { ports: ["127.0.0.1:3000:3000"] }   # NEXTAUTH_URL 이 http://localhost:3000
  rabbitmq:      { ports: ["127.0.0.1:15672:15672", "127.0.0.1:5672:5672"] }   # 15672 관리 UI · 5672 IDE에서 띄운 앱이 붙을 때
  minio:         { ports: ["127.0.0.1:9001:9001"] }
  prometheus:    { ports: ["127.0.0.1:9090:9090"] }
```

**ops 프로필 `.env` 변수** (값은 노션 `.env` 페이지, compose에 기본값 없음)

| 변수 | 도구 | 무엇 |
|---|---|---|
| `GRAFANA_ADMIN_PASSWORD` | grafana | 관리자(`admin`) 비밀번호 |
| `REDIS_PASSWORD` | redis · langfuse · app | Redis 비밀번호. 앱도 이 값으로 붙어요(`AML_SESSION_REDIS_ENABLED=true`일 때) |
| `RABBITMQ_USER` · `RABBITMQ_PASSWORD` | rabbitmq · app | 관리 계정. 앱도 이 계정으로 붙어요(`AML_RABBITMQ_ENABLED=true`일 때) |
| `MINIO_ROOT_USER` · `MINIO_ROOT_PASSWORD` | minio · minio-init · langfuse | MinIO 루트 계정 |
| `ELASTIC_PASSWORD` | elasticsearch | `elastic` 계정 비밀번호 |
| `CLICKHOUSE_PASSWORD` | clickhouse · langfuse | ClickHouse 비밀번호 |
| `LANGFUSE_DB_PASSWORD` | langfuse-db-init · langfuse | Postgres `langfuse` 계정 비밀번호 |
| `LANGFUSE_NEXTAUTH_SECRET` · `LANGFUSE_SALT` | langfuse | 로그인 세션 서명·API 키 해시용 난수 |
| `LANGFUSE_ENCRYPTION_KEY` | langfuse | 16진수 64자(`openssl rand -hex 32`) |
| `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` · `LANGFUSE_INIT_PROJECT_SECRET_KEY` | langfuse | 첫 프로젝트 API 키(`pk-lf-…`·`sk-lf-…`) — AI `.env`에 같은 값 |
| `LANGFUSE_INIT_USER_EMAIL` · `LANGFUSE_INIT_USER_PASSWORD` | langfuse | 첫 관리자 로그인 |
| (기존) `POSTGRES_PASSWORD` | langfuse-db-init | `langfuse` DB·계정을 만들 때 |
| (선택) `COMPOSE_PROFILES` | — | `ops`면 `docker compose up`에 도구까지 |

`LANGFUSE_DB_PASSWORD`·`REDIS_PASSWORD`·`MINIO_ROOT_USER`·`MINIO_ROOT_PASSWORD`는 접속 주소(URL) 안에 그대로 들어가요 — **영문·숫자만** 써요(`@`·`:`·`/`·`#` 등이 있으면 주소가 깨져요).

**문제 해결** — elasticsearch가 unhealthy이고 로그에 `flood stage disk watermark [95%] exceeded`가 보이면 Docker 디스크가 찬 거예요(ES가 `elastic` 계정 인증까지 못 해요). `docker system df`로 확인하고 `docker builder prune`(빌드 캐시)로 비우거나 Docker Desktop 설정의 디스크 한도를 늘린 뒤 `docker compose restart elasticsearch`.

**ClickHouse 예전 로그 테이블 정리 — 11/18(SCRUM-287) 전에 ops를 띄운 적 있으면 한 번만** — 메모리를 아끼려고 Langfuse가 읽지 않는 시스템 로그 7개를 `ops/clickhouse/low-memory.xml`에서 껐어요. 그런데 그전에 만들어진 테이블은 볼륨에 남아서, ClickHouse가 예전 파트를 계속 합치느라 메모리를 써요(그러면 `memory limit exceeded`가 나고 Langfuse 기록이 버려져요). **새 볼륨으로 처음 띄우는 사람은 이 테이블이 생기지 않으니 필요 없어요.** 순서가 중요해요:

1. develop을 받은 뒤 **먼저** 새 설정으로 ClickHouse를 다시 띄워요.
2. **그다음** 아래 DROP 명령을 한 번 실행해요(두 번 돌려도 괜찮아요 — `IF EXISTS`). 비밀번호는 컨테이너 환경 변수를 읽어서 화면에 찍히지 않아요.

거꾸로 하면(옛 설정으로 도는 서버에서 DROP) 아직 꺼지지 않은 로그가 다음 기록 때 테이블을 다시 만들어요.

```bash
docker compose --profile ops up -d --force-recreate clickhouse
```

```bash
docker compose --profile ops exec clickhouse sh -c 'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --multiquery -q "DROP TABLE IF EXISTS system.trace_log SYNC; DROP TABLE IF EXISTS system.text_log SYNC; DROP TABLE IF EXISTS system.opentelemetry_span_log SYNC; DROP TABLE IF EXISTS system.asynchronous_metric_log SYNC; DROP TABLE IF EXISTS system.metric_log SYNC; DROP TABLE IF EXISTS system.latency_log SYNC; DROP TABLE IF EXISTS system.processors_profile_log SYNC"'
```

확인 — ClickHouse는 로그를 **컨테이너 안 파일**에 써요. `docker compose logs clickhouse`에는 설정 파일 안내 몇 줄만 나와서, 거기서 `memory limit`을 grep하면 늘 0줄이에요(아무것도 확인하지 못해요). `--profile ops`를 빼도 서비스를 못 찾는 오류만 나고 grep 결과는 0이라 확인된 것처럼 보여요. 아래처럼 컨테이너 안 오류 로그를 봐요. 로그 폴더는 볼륨이 아니라 `--force-recreate`로 다시 띄우면 새로 시작해요. 옛 로그는 번호가 붙고 압축되기도 해서 `zcat -f`로 둘 다 읽고, 시각은 정렬해서 봐요(로그 줄이 날짜로 시작해서 파일 순서와 상관없어요). **명령이 오류(`No such file`·`Permission denied`·gzip 오류)를 내면 확인 실패예요 — 숫자나 "없음"·시각이 나올 때만 확인된 거예요.**

- 다시 띄운 뒤 `memory limit` 줄 수 — 0이면 돼요:

```bash
docker compose --profile ops exec clickhouse sh -c "ls /var/log/clickhouse-server/clickhouse-server.err.log* && zcat -f /var/log/clickhouse-server/clickhouse-server.err.log* | grep -c 'memory limit'"
```

- 다시 만들지 않은 컨테이너라면 마지막 `memory limit` 줄 시각(UTC) — DROP 전이면 돼요(한 번도 없으면 "없음"):

```bash
docker compose --profile ops exec clickhouse sh -c "ls /var/log/clickhouse-server/clickhouse-server.err.log* && { zcat -f /var/log/clickhouse-server/clickhouse-server.err.log* | grep 'memory limit' | sort | tail -n 1 | cut -c1-30 | grep . || echo 없음; }"
```

- 서버가 언제 시작됐는지(UTC) — `uptime()`은 맥이 잠든 동안 늘지 않아 실제보다 짧게 나와서 이 줄로 봐요:

```bash
docker compose --profile ops exec clickhouse sh -c "ls /var/log/clickhouse-server/clickhouse-server.log* && zcat -f /var/log/clickhouse-server/clickhouse-server.log* | grep 'Starting ClickHouse' | sort | cut -c1-60"
```

## 지금 들어 있는 것 (W1 뼈대, 10/26)

| 묶음 | 파일 | 상태 |
|---|---|---|
| 폴더 구조 | `com.team.aml.{source,detection,cases,str,user,shared}` × `domain/application/infrastructure/api` | 각 폴더 `package-info.java`에 담당·들어갈 클래스 적어 둠 |
| DB | `db/init/00_extensions.sql`(pgvector), `db/init/01_roles.sh`(역할 3개·스키마 2개), Flyway `V001`~`V004` (권한, 원천 4개+스테이징, users+SYSTEM, batch_runs), `V004_1`(source_files), `V004_2`(ai_call_logs), `V004_3`(access_logs) | 레퍼런스 3절 그대로. `ddl-auto=validate` |
| shared/domain | `AuditedEntity`(감사 4, SCRUM-68), `BaseEntity`(+ 자동 id), `AssignedIdEntity`(+ 미리 받은 id, SCRUM-68), `CreatedOnlyEntity`, `DomainException`, `NotFoundException`, `ForbiddenException`(403 부모, SCRUM-67), `DisplayNumber` | |
| shared/api | `GlobalExceptionHandler`(8.2 에러 JSON), `ErrorResponse`, `PageResponse` | |
| shared/security | `SecurityConfig`(세션·CSRF·URL 역할), `CurrentUser`, `SessionAuditorAware`, 401/403 JSON, `request_id` MDC | 진짜 |
| shared/audit | `AccessLogWriter`(열람 기록 한 줄 — `view`·`strView`·`pdfDownload`·`export`), `LoginAccessLogWriter`(로그인·로그아웃, security 전용), `AccessLog` | 진짜 (SCRUM-19) |
| shared/batch | `BatchRun`, `BatchRunRepository`, 409 예외 2개 | 안건 14 반영 |
| shared/ai | `AiClient` 인터페이스, DTO 틀, `MockAiClient`(local/test), 기동 시 health, `AiCallLog`·`AiCallLogWriter`(시도마다 1행, 메타데이터만) | 진짜 (SCRUM-17·18) |
| user | `User`, `Role`, `UserRepository`, `GetMe`, `AuthController(/me)`, `DbUserDetailsService`(로그인 때 users 읽기), `DevUserSeeder` | 진짜 (SCRUM-19) |
| 테스트 | `ArchitectureTest`(ArchUnit, DB 없이), `ApplicationContextTest`(Testcontainers, Docker 필요) | |
| 자료 | `src/test/resources/fixtures/ibm_small/`(축소본 — 지금은 합성 샘플), `tools/fixture/`, `docs/flows/` | |

### 로그인 방법

`POST /api/v1/auth/login` 본문 `{"login_id", "password"}` — 개발 계정은 `admin`·`inv01`·`apr01`·`inv02`, 비밀번호는 `.env`의 `DEV_USER_PASSWORD`(값은 노션, local 프로파일에서 `DevUserSeeder`가 만든다).
성공 200 `{id, login_id, display_name, role}`, 실패 401 `LOGIN_FAILED`(없는 ID·틀린 비밀번호·비활성 모두 같은 응답). 그 뒤: 세션 쿠키 `SESSION`, CSRF(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더),
같은 계정 동시 세션 1개, 30분 만료, `GET /auth/me`, `POST /auth/logout`(204), 401/403 JSON. 다른 사람 코드는 전부 `CurrentUser`로 사용자를 꺼낸다.

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
