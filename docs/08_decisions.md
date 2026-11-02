# 08. 결정 기록 · 문서끼리 다른 곳 · 미결

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

## 1. 백엔드에 영향 있는 확정 결정

| 날짜 | 결정 | 이유 | 출처 |
|---|---|---|---|
| 10/14 | 세션 쿠키 로그인, JWT 안 씀 | 내부 시스템, 서버가 즉시 끊을 수 있음 | 레퍼런스 §9 |
| 10/14 | 테이블 PK는 bigint, 사람이 보는 번호는 따로 / 감사 4 / 이력 추가만 / AI 응답 jsonb / `ops`·`ai` 분리 | 나중에 바꾸면 모든 테이블을 고쳐야 함 | 가이드 §2 |
| 10/22 | `batch_runs`는 `shared/batch` | 적재(source)도 써서 detection에 두면 의존 방향이 깨짐 | 안건 14, 레퍼런스 v0.4 |
| 10/22 | 데이터 숫자 508만 건(5,078,345)으로 통일 | 697만은 LI-Small 오기 | 안건 18 |
| 10/23 | 에스컬레이션은 별도 버튼, 초안 생성 요청 안에서 하지 않음 | 상태 변경과 90초 AI 호출을 섞지 않음 | 가이드 §5.1 |
| 10/27 | `transaction_id`는 bigint, DB 자동 발급 + `source_files`로 원본 추적 | 다중 CSV 확장 대비, 구조만 먼저 | 안건 1, ML 규약 §1.1 |
| 10/27 | 등급(HIGH/MEDIUM/LOW)은 cases `CasePolicy`가 계산. AI는 점수·추천 임계값만 | 모델 점수와 운영 정책 분리 | 안건 2 |
| 10/27 | `data_quality_status` 4값. 점수 실패는 저위험이 아님 | 실패 경보가 사라지면 안 됨 | 안건 4 |
| 10/27 | 근거 거래 `evidence`(경보 안)와 `context`(경보 밖) 분리. STR은 evidence만 | 참고 거래 상대방을 혐의자처럼 쓰지 않음 | 안건 7 |
| 10/27 | 검증 통과 = 코드 검사 전부 + 평가자 전부 4점 이상. 평가자 실패는 `VERIFY_UNAVAILABLE` | 숫자만 맞고 근거 없는 보고서 방지 | 안건 9 |
| 10/27 | 점수 배치 500건·동기로 시작, W4 실측 후 설정값만 조정 | 실측 전 | 안건 10 |
| 10/27 | ESCALATED·DRAFTED → CLOSED 허용(사유 필수) | 초안까지 만들고 오탐인 경우 | 안건 11 |
| 10/27 | 서비스 DB 적재는 백엔드(준혁), 검증값은 준영 `expected.json`으로 같이 확인 | 계정 분리 유지, 정규화 규칙 한 벌 | 안건 13 |
| 10/27 | 재실행 — 적재·룰 스캔은 성공 후 금지, 점수·케이스 생성은 남은 대상 재처리 허용. `SCORE_RETRY` 없이 점수 재실행 | 재시도 뒤 새 HIGH의 케이스 생성이 막히면 안 됨 | 안건 14·21 |
| 10/27 | 메모는 `case_history` NOTE 행이 권장 — **이름 확인 남음** | 이력 원칙과 같음 | 안건 16 |
| 10/27 | 초안 생성·검증 버튼 분리, 자동 검증 없음 | 90초 + 90초는 길다 | 안건 17 |
| 10/27 | 금액 `numeric(18,2)` 유지 〔11/2 `numeric(20,6)`으로 바뀜 — 아래 11/2 줄〕 | 서비스 DB는 IBM만 | 안건 19 |
| 10/27 | 룰 공통 네 가지(규약 번호·통화별·달력일·자기계좌 제외), 대표 룰 R02 | 백엔드 룰과 학습용 룰 정합 | 안건 20 |
| 10/27 | `AiClient` 재시도 1회 — `LLM_UNAVAILABLE`·`MODEL_UNAVAILABLE`·연결 계열 5xx만, 그 밖 0회. `alert_scores.alert_id` UNIQUE 유지 | 현값 유지 | 안건 21, 규약 v0.2 §6.2 |
| 10/27 | AI가 facts 계산, `draft_id` 백엔드 선발급, 검증은 `generation_id` + 본문 해시로 묶음 | facts·검증이 한 기준 | 안건 23 |
| 10/27 | 반려 두 갈래(`NEED_REVISION`/`NOT_REPORTABLE`) + 사유 필수, 같은 본문 재요청 409, PENDING_APPROVAL → CLOSED 없음, 반려 코드 검사는 cases 값 | 반려 판단을 데이터로 | 안건 24 |
| 10/28 | 상태 전이 에러 이름 `INVALID_CASE_TRANSITION`으로 통일 | 문서마다 달랐음 | STR 문서, CASES 명세 |
| 10/28 | `reasons` = 관측·기준·기여 분리, 확인 못 한 값 null. STR은 관측값만 | 기여값을 관측값처럼 쓰는 위험 | 안건 3 |
| 10/28 | R04 최대 3홉, R05 유지, R06 교체(대체 룰 미정), R04·R05는 `rule_facts.paths` | 일정·구현 난이도 | 안건 20(준혁), ML 규약 §2.4 |
| 10/29 | AI 호출 시간 한도는 **화면 요청(초안·검증·요약) 전체 90초**(재시도 포함), 점수 배치는 묶음 호출마다. 재시도 1회는 남은 시간 안에서 AI가 바로 돌려준 `LLM_UNAVAILABLE`·`MODEL_UNAVAILABLE`·연결 거부만, 백엔드 쪽 타임아웃은 재시도 안 함 | 재시도까지 하면 180초가 될 수 있었음 | 설계 피드백 4, 규약 §6.2 |
| 10/29 | AI 409 `GENERATION_IN_PROGRESS`는 프론트에 409 `DRAFT_IN_PROGRESS`로 (일괄 502 아님) | "생성 중"과 AI 장애를 화면이 구분해야 함 | 설계 피드백 5 |
| 10/29 | `/approvals/**` 권한은 메서드별 — GET ADMIN·APPROVER, POST APPROVER (`SecurityConfig`는 SCRUM-19에서 반영) | 권한표(ADMIN 조회 가능)와 코드가 달랐음 | 설계 피드백 5, 계정·권한 16~18번 |
| 10/29 | `ai_call_logs`는 메타데이터만, 실패해도 요청·응답 본문 저장 안 함. 전문은 AI가 그 요청을 저장했을 때만 AI 쪽에 있음 | 실패 요청에 조사 메모·본문이 들어갈 수 있음, 규약 §6.4와 맞춤 | 설계 피드백 6 |
| 10/29 | 점수 응답은 경보별 실패 — 없는 거래 ID는 그 경보만 `FAILED`, 재처리는 `FAILED` 중 `score_failure_code`가 NULL(묶음 503 — 응답 자체가 없음)이거나 `COMPUTE_ERROR`인 경보만. 백엔드는 `alerts.score_failure_code`, 실패 경보는 `alert_scores` 행 없음, `data_quality_status`는 `payload`. AI DB 장애는 503 `DB_UNAVAILABLE` → `AiClient` 재시도 1회 대상에 추가. 점수는 경보당 1건(이력은 AI `ai.predictions`) | 500건 중 1건 때문에 전체가 실패하면 안 됨, 다시 보내도 같은 실패는 걸러야 함 | 준혁 답(10/29 슬랙), AI SCRUM-41 |
| 10/29 | 목록 응답은 `{items, page, size, total}` — 코드를 문서에 맞춤, `total_pages` 없음 | 문서가 전부 이 모양, 쓰는 코드가 아직 없어 지금 바꾸는 비용이 0 | 레퍼런스 §12, SCRUM-39 |
| 10/30 | `flows/02`·`flows/05`·`04_auth_user`를 미결 8·11·17, 서식 안건 4 결정에 맞춤 — 상세+현재 초안은 str 조회 유스케이스가 조합(REPEATABLE READ), `case_history` action 동사형, 사용자 관리 `POST /admin/users/{id}/{동사}`, STR 본문 열람은 담당 조사역·승인자만 | 요구사항 정의서를 노션과 대조하다 리포 문서가 옛 내용으로 남은 곳이 나옴 | 백엔드 미결 10/30, 계정·권한 안내 v0.2 |
| 10/30 | `ai_call_logs`는 **시도 1번 = 1행**(재시도는 같은 `request_id`에 `attempt` 1·2). 본문 컬럼 없음, FK 없음, `created_at`만(베이스 클래스 상속 안 함). `target_id`는 `varchar(64)`, `target_type`은 `BATCH_RUN`·`CASE`·`DRAFT` CHECK(health는 null). `ai_version`은 엔드포인트별로 고정(`03_shared` §5 표) | `AiClient` 시그니처를 안 바꾸고 요청 DTO의 `batchRunId`·`caseId`·`draftId`에서 대상을 꺼냄 — 팀원 호출부 영향 없음 | SCRUM-18 |
| 10/30 | `ai_call_logs` 기록은 **별도 트랜잭션**(`REQUIRES_NEW`, 별도 빈)으로, AI 응답을 받은 **호출 스레드에서**. 기록이 실패해도 AI 결과·예외는 그대로(호출부에서 삼킴) | 바깥 작업이 롤백돼도 실패 행이 남아야 함(`flows/04`), 커밋 때 예외는 프록시 밖으로 나옴 | SCRUM-18 |
| 10/30 | `ai_call_logs`는 **V004_2**(11/1 SCRUM-56에서 V007에서 옮김) — FK가 없어 V004_1 바로 뒤에 들어감 | 번호가 낮은 파일이 나중에 들어오면 이미 높은 번호가 적용된 DB에서 Flyway가 기동을 거부함 | SCRUM-18, `05_flyway` §3 |
| 10/31 | 진짜 로그인은 **JSON 로그인 필터**(`UsernamePasswordAuthenticationFilter` 상속)를 **`AbstractAuthenticationFilterConfigurer`로 등록** — 세션 ID 교체·`maximumSessions(1)`·세션 저장·CSRF 토큰 교체를 Spring 전략으로. 끊긴 세션은 401 `UNAUTHENTICATED` JSON. 비활성·SYSTEM은 없는 ID와 같은 401 `LOGIN_FAILED`. users 읽기는 `user/infrastructure/DbUserDetailsService`. 목업 로그인 삭제 | 필터를 `addFilterAt`으로만 끼우면 세션 전략이 빠져 로그인이 세션에 안 남음(테스트로 확인). shared → user 의존 금지 | SCRUM-19 |
| 11/1 | 권한 문제(역할·담당자)로 막히는 도메인 예외는 shared `ForbiddenException` 하위 → **403**, 코드는 클래스 이름 규칙(`NOT_APPROVER`·`NOT_ASSIGNEE`). 상태·버전 충돌은 `DomainException` 409 그대로(`SELF_APPROVAL` 포함). 승인 게이트는 **같은 초안·같은 본문 해시의 검증 중 최신만** 인정 — 최신이 PASSED가 아니거나 요청 `verification_id`가 최신과 다르면 409 `DRAFT_NOT_VERIFIED` | 역할·담당자가 안 맞는 건 상태 충돌이 아니라 권한 문제라서 03_shared 표대로 403 [백엔드 #22 리뷰 11/1] | 백엔드 #22 리뷰(11/1), SCRUM-67 |
| 11/2 | Flyway 번호 재배정 — `ai_call_logs` V007 → **V004_2**, `access_logs` **V004_3**, `alert_scores` **V005_1**, V007은 비움. 머지 순서 V004_1 → V004_2 → V004_3 → V005 → V005_1 → V006 → V008 → V009. **양보 규칙**: 하위 번호로 끼운 파일이 뒤 번호 머지를 막으면 끼운 쪽이 다음 빈 번호로 옮김 | 로그 테이블 둘은 탐지·케이스를 참조하지 않아 V005·V006을 기다릴 이유가 없음. `alert_scores`는 V008·V009를 기다리지 않게 V005 바로 뒤. V007을 당겨 채우면 V008·V009와 문서를 다 고쳐야 함 | SCRUM-56, `05_flyway.md` §1·§3 |
| 11/2 | `access_logs`(V004_3) — action 이름 `STR_VIEW`·`PDF_DOWNLOAD`(나머지 8개 그대로). 기록은 모두 **별도 트랜잭션**(`REQUIRES_NEW`). 팀원은 `shared/audit/AccessLogWriter`의 action별 메서드(`view`·`strView`·`pdfDownload`·`export`)만 부르고 사용자·IP는 Writer가 꺼냄. 로그인용 `LoginAccessLogWriter`는 `shared/security`만. **`LOGIN_FAIL`은 인증까지 간 실패(401)만** — 400 형식 오류는 기록 안 함. `login_id_attempted`는 앞 64자, IP는 `getRemoteAddr()`. **열람 기록 저장이 실패하면 조회도 실패**(예외 전파), 로그인·로그아웃 기록 실패는 응답 그대로 + 경고 로그 | 조회 유스케이스는 보통 readOnly라 같은 트랜잭션에선 INSERT가 거부됨. 문자열 action을 밖에서 못 넣게 해 값이 흩어지지 않음. 400은 인증 시도가 아니라 형식 오류. 기록 없는 열람은 감사 공백이라 막음, 로그인은 기록 때문에 막히면 안 됨 | SCRUM-19, 미결 10, 11/2 진희 |
| 11/2 | 시퀀스로 미리 받은 번호를 id로 쓰는 엔티티(`str_drafts`·`str_draft_requests`)는 shared **`AssignedIdEntity`** 상속. 감사 4칸은 새 부모 `AuditedEntity`로 올리고 `BaseEntity`는 그 위에 IDENTITY id만(공개 메서드 그대로). **새 행 판단은 `Persistable.isNew()`** — 생성 직후 true, `@PostPersist`·`@PostLoad` 뒤 false. version 칸으로 판단하지 않음 | id를 직접 넣으면 `save()`가 `merge`로 가서 SELECT가 더 나가고 같은 id를 조용히 덮어씀. `str_draft_requests`엔 version 칸이 없어 "version null = 새 행"도 못 씀 | 백엔드 #28 리뷰(11/2), 미결 21, SCRUM-68 |
| 11/2 | 거래 금액 `numeric(18,2)` → **`numeric(20,6)`** (V005_2). 원본 금액을 반올림하지 않음 | 실제 HI-Small에서 Bitcoin 소수 6자리 필드 294,217개 중 293,425개 값이 바뀌고 일부는 0.00이 됨. 정수부 최대 13자리라 14자리면 충분 | 안건 19, 준혁·준영 합의(ML 규약 하위 「B7 기준 및 후속 협의 사항」), SCRUM-97 |

## 2. 문서끼리 다른 곳 — 이 docs에서 고른 것

원칙: **더 최근 문서·회의 결정을 따름.** 담당자 초안이 회의 결정 전에 쓰였으면 회의 결정을 따랐어요.

| 주제 | 문서 A | 문서 B (더 최근) | 이 docs에서 |
|---|---|---|---|
| `transaction_id` | CSV 행 번호를 그대로 PK [레퍼런스 §2, SD 명세 §11.2, 규약 v0.2 §1.1 비고 칸] | DB 자동 발급 + `source_files` [안건 1, ML 규약 §1.1] | B |
| `score_band` 결정 | AI가 줌 [레퍼런스 §14.1] / detection 경계 [SD 명세 §13-8] | cases `CasePolicy` [안건 2] | B |
| 점수 재시도 | `SCORE_RETRY` + `/score/retry` [SD 명세 §7] | 점수 배치 재실행 [안건 14·21] | B |
| 점수 행 수 | 경보 하나에 여러 행 [레퍼런스 §3.2, SD 명세] | `alert_id` UNIQUE 유지 [안건 21, ML 규약 §6.3] | B |
| 점수 응답 모양 | 배열 + 항목별 `model_version` [SD 명세 §11.4] | `{batch_run_id, scores, scoring_meta, produced_at}` [ML 규약 §3] | B |
| ESCALATED·DRAFTED → CLOSED | 불허 [레퍼런스 §14.1-6] | 허용 [안건 11] | B |
| 코드 검사 수 | 4종 [STR 문서·명세] | 5종 [규약 v0.2 §5.3] | B |
| 에러 응답 모양 | `{code, message, trace_id}` [계정·권한 §5] | `{error:{code, message, details}}` [레퍼런스 §8.2, 코드] | 레퍼런스(계정·권한 스스로 "레퍼런스가 맞다") |
| 409 동시 수정 이름 | `CONFLICT` [계정·권한 §5] | `CONCURRENT_UPDATE` [레퍼런스 §8.2, CASES·STR 명세] | `CONCURRENT_UPDATE` |
| 요약 갱신 경로 | `POST /cases/{id}/summary:refresh` [계정·권한] | `POST /cases/{case_no}/refresh-summary` [CASES 명세 v0.5] | B 〔초안〕 |
| 메모 경로 | `POST /cases/{id}/notes` [계정·권한] | `PUT /cases/{case_no}/notes` [CASES 명세] | B 〔초안〕 |
| 마이그레이션 번호 | V007 `alert_scores` [레퍼런스 §7.3] | V007 `ai_call_logs` [README, WBS] | B → 11/2 SCRUM-56에서 `ai_call_logs`를 V004_2로 옮김, V007 비움 (§1 표) |
| `ai_call_logs` 본문·대상 | `request_body`·`response_body`(실패만), `target_id bigint` [레퍼런스 §3.4] | 본문 컬럼 없음, `target_id varchar(64)` [10/29, SCRUM-18] | B — 레퍼런스 §3.4 반영됨(10/30) |

## 3. 미결 — 정해야 하는 것

**팀 의견은 노션 [백엔드 미결 · 팀 결정](https://app.notion.com/p/4077fbce53048094a838f49ca1bde5d4)에 달아 주세요.** 거기서 정해지면 이 표와 해당 문서를 같이 고쳐요.

| # | 무엇 | 지금 상태 | 누구 |
|---|---|---|---|
| 1 | 승인·반려 경로 `{draft_id}` vs `{case_no}` | 계정·권한·STR 명세는 draft_id, CASES 명세는 case_no 유지안 제안 | 채영·지윤 |
| 2 | 초안 있는 종료 경로 `POST /cases/{case_no}/close-with-draft` | CASES·STR 명세 공동 제안, 확인 요청 | 진희·지윤 |
| 3 | ~~목록 응답 모양~~ | 코드를 문서에 맞춤(§1 표). SCRUM-39 머지되면 끝 | 진희 |
| 4 | `batch_runs` 재실행 인덱스 | `V004`는 네 배치 다 막음 → 적재·룰 스캔으로 좁히는 새 파일 필요. `RUNNING` 조건 추가, `PARTIAL` 상태, `metrics jsonb`도 SD 명세 제안 | 진희·준혁 |
| 5 | ~~AI 재시도 대상~~ | 규약 v0.2 §6.2로 정리됨(§1 표). 레퍼런스 §8.5 문구만 고치면 됨 | 진희 |
| 6 | 은행 ID 앞 0 | 두 파일 형식이 달라 조인이 100% 실패 vs "앞 0 보존" 문구 | 준혁 |
| 7 | 통화 표기 | DB는 IBM 원문 이름, STR facts는 ISO 코드. 백엔드 화면·`rule_facts`는? 매핑표 관리자는? | 준혁·진희 |
| 8 | 메모 저장 방식 | NOTE 행 vs `case_note_history` (안건 16 A/B 이름 엇갈림) | 지윤·진희 |
| 9 | ~~`alert_scores`·`access_logs`·`source_files` 마이그레이션 번호~~ | 정리됨 — 11/2 SCRUM-56: `source_files` V004_1(#21), `ai_call_logs` V004_2, `access_logs` V004_3, `alert_scores` V005_1. `batch_runs` 인덱스는 점수 배치 직전(§1 표, `05_flyway.md` §3) | 준혁·진희 |
| 10 | ~~`access_logs.action` 값~~ | 정리됨 — 11/2 SCRUM-19: 10개(`STR_VIEW`·`PDF_DOWNLOAD` 이름 확정, §1 표, `04_auth_user.md` §1) | 진희 |
| 11 | `user` 관리 API 메서드 | 계정·권한은 PATCH, 레퍼런스 §12는 "PATCH 없음" | 진희 |
| 12 | 감사 시각 표기 | 응답에서 UTC `Z`를 붙일지 (원천 거래 시각은 시간대 없음 — 확정). 〔제안〕 붙이자 — `01_code_rules.md` §5 | 공통 |
| 13 | "신규 제안" 에러 코드 | SD 명세 6개, CASES 명세 3개 | 진희 확인 후 반영 |
| 14 | 적재 검증값 | 계좌 수 518,573 vs 518,581, 패턴 거래 3,209 재확인 | 준혁 |
| 15 | 룰 세부 | R06 대체 룰, R05 수치 한도, R08 기준, 룰 스캔 날짜 범위, 섞인 통화의 `rule_facts` | 준혁·준영 |
| 16 | ~~점수 공개 실패 코드(`failure_code`)~~ | 10/29 준혁 확인으로 확정(§1 표). 규약 §3·§6.3은 규약 변경 요청 | 진희 |
| 17 | 상세 화면에 현재 초안을 합치는 위치 | cases → str 의존 금지 | 지윤·채영·진희 |
| 18 | 서식 회의 (승인 이후·PDF·7항목·열람 제한·DB 변경) | **회의 전**, 의견 칸 비어 있음 | 전원 |
| 19 | `account_key`로 계좌번호 마스킹이 무력화됨 | 프론트 응답에서 뺄지·마스킹할지, 화면 계좌 식별자 (설계 피드백 1) | 진희·지윤·채영 |
| 20 | 본문 수정과 승인 요청이 동시에 들어올 때 | 서로 다른 행만 바꾸면 버전 검사가 둘 다 통과 — 공통 행 잠금 순서 (설계 피드백 2) | 채영·지윤 |
| 21 | 초안 재전송 때 같은 `draft_id` 유지 | 응답 유실 후 다시 누르면 새 `draft_id` — 선점·재전송·재생성 구분 (설계 피드백 3) | 채영 |
| 22 | 화면 집계 vs STR facts 집계 | AI facts의 `internal`(같은 법인 다른 계좌)과 화면의 자기계좌 제외가 달라 숫자가 다를 수 있음 (설계 피드백 8) | 지윤·진희·준혁 |
| 23 | HIGH 판정 기준 | 규약 §3에 `score ≥ threshold → HIGH`가 남아 있음 — HIGH는 CasePolicy `medium_to_high`, AI threshold는 추천값으로 고치기 (규약 변경) | 지윤·진희·준영 |
| 24 | STR 수정 길이 | 백엔드 5,000자 vs AI 검증 스키마 1,200자 — 생성 1,200 / 수정본 검증 5,000 분리, 글자 수 기준 통일 | 채영·진희 |
| 25 | `revision_no` | 규약·AI는 1 = AI 원문, STR 명세는 원문 `null` — 규약대로 맞추기 | 채영·진희 |
| 26 | 경로형 경보 `paths` 계약 | 합집합 = `transaction_ids`·R04 3홉(백엔드·ML) vs 부분집합·길이 제한 없음(AI) — 400과 UNVERIFIED 경계 정하기 | 준혁·진희·준영 |
| 28 | health 응답 버전 필드 | 백엔드 `HealthResponse`는 `generator_version`을 읽는데 AI `/v1/health`는 `server_version`·`model_version`·`feature_version`·`retrieval_version`을 줌 → 기동 로그에 null. SCRUM-17 코드라 SCRUM-22(BE↔AI 연결)에서 맞춤 (11/1 SCRUM-18 점검) | 진희(SCRUM-22) |
| 29 | `ai_call_logs` drafts 대상 | 지금 `CASE`/`caseId`인데 안건 23 이후 백엔드가 `draft_id`를 먼저 발급함 → `AiDtos`를 규약 v0.2 모양으로 바꿀 때(SCRUM-22) `DRAFT`/`draft_id`로 갈지 정함 (11/1 SCRUM-18 점검) | 진희(SCRUM-22) |
| 30 | 403 시도 기록 | "권한이 없어 403이 난 시도도 남김"(NFR-COM-01·FR-APR-02)인데 맞는 action이 없음 — 별도 `ACCESS_DENIED` 추가 vs 원래 action + 결과 칸. SCRUM-19 B에서는 뺌 | 진희 |

## 4. 이 docs 자체에 대한 결정

| 날짜 | 결정 | 이유 |
|---|---|---|
| 10/29 | 백엔드 docs는 노션·리포를 모은 **요약본**. 정본은 노션·코드 | 같은 내용을 두 곳에 두면 어긋남 |
| 10/29 | 확정 / 〔초안〕 / 〔확인 필요〕 세 표시로 구분, 리뷰 기준은 확정만 | 담당자 문서가 아직 보완 중 |
| 10/29 | 노션에 없는 진희 제안 6개는 각 문서 본문에 **〔제안〕 표시로** 넣음 (`제안.md`는 없앰). 리뷰에선 `[제안]` 코멘트로만 | 팀원이 본 적 없는 규칙으로 `[반드시]` 지적하지 않음. 팀이 받아들이면 노션에 넣고 표시를 지움 |
| 10/29 | 리포 `docs/`에 올리고(SCRUM-39 PR), 팀원 리뷰로 확인 | 팀원이 같이 보고 고칠 수 있게 |
| 10/29 | `06_features`는 담당자별 파일로 나눔, `09_decisions` → `08_decisions` | 같은 파일 동시 수정 충돌 방지, 번호 빈칸 없애기 |
