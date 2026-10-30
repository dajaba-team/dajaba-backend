# 05. Flyway 마이그레이션

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

DB 구조는 **Flyway SQL 파일만이 정답**이에요. JPA가 테이블을 만들지 않고, 누구도 DB 툴에서 직접 ALTER를 치지 않아요. 세 사람의 로컬 DB가 서로 달라지는 사고를 막는 유일한 방법이에요. [레퍼런스 §7]

## 1. 규칙 [레퍼런스 §7.1, README, PR 템플릿]

| 규칙 | 내용 |
|---|---|
| 위치 | `src/main/resources/db/migration/` |
| 파일 이름 | `V{3자리 번호}__{영어 snake_case 설명}.sql` (예: `V005__create_detection_tables.sql`) |
| 한 PR 한 파일 | PR에 마이그레이션은 최대 하나 |
| **번호 예약** | PR 열기 전에 팀 채팅에 "V0xx 쓰겠습니다" 한 줄. 같은 번호가 둘이면 Flyway가 기동을 거부해요. 〔제안〕 채팅과 함께 노션 예약 표(§3 모양)에도 한 줄 — 지나간 채팅을 찾지 않아도 충돌을 미리 볼 수 있어요 |
| **머지 순서** | 머지는 **번호 순서대로**(PR은 언제든 열어도 돼요). 낮은 번호가 나중에 들어오면 이미 높은 번호가 적용된 DB에서 Flyway가 기동을 거부해요 |
| 하위 번호 | **이미 예약된 번호 앞에 끼울 때만** `V004_1__…` 같은 하위 번호. Flyway는 `4.1`로 읽어서 V004와 V005 사이에 적용해요. 그 밖에는 다음 정수 번호 |
| 적용된 파일은 불변 | 이미 들어간 파일은 고치지 않아요. 바꿀 게 있으면 다음 번호로 `ALTER` |
| 파일 하나 = 테이블 한 묶음 | 테이블 + 인덱스 + CHECK + 초기 데이터(예: `rules` 8개)를 한 파일에 |
| 롤백 없음 | 무료판엔 undo가 없어요. 잘못 들어가면 다음 번호로 되돌리는 SQL |
| `ddl-auto` | `validate` 고정. `update`·`create`는 어떤 프로파일에도 없어요. 엔티티와 테이블이 안 맞아 기동이 실패하면 **답은 항상 "마이그레이션 파일을 쓴다"** |
| 실행 계정 | Flyway는 `app_migrate`(DDL), 앱은 `app_backend`(DDL 권한 없음) [코드 `application.yml`] |

**쓸 때 지키는 것** [레퍼런스 §3·§7]
- 상태·유형 값은 DB enum 대신 `varchar(32)` + `CHECK`(값 추가가 쉬움).
- 표에서 null 표시가 없으면 `not null`.
- 이력 테이블(추가만)은 같은 파일에서 `revoke update, delete on ops.{테이블} from app_backend;`
- 서로 가리키는 FK(예: `cases.current_draft_id` ↔ `str_drafts`)는 나중 파일에서 `alter table … add constraint`로.
- `ai` 스키마를 가리키는 FK 금지.
- 로컬 DB 초기화는 `docker compose down -v`. `flyway:clean`은 쓰지 않아요.
- 원천 508만 행 적재는 마이그레이션이 아니라 `LoadIbmDataset`(COPY)로.

## 2. 이미 들어간 파일 [코드 develop `7477916`]

| 번호 | 파일 | 내용 |
|---|---|---|
| V001 | `create_schemas_and_roles` | `ops`·`ai` 스키마, 계정 3개 권한, `app_ai`에 원천 4개 SELECT |
| V002 | `create_source_tables` | 원천 4개 + `transactions_staging`(unlogged). 금액 `numeric(18,2)` |
| V003 | `create_users` | `users` + `SYSTEM`(id=1) |
| V004 | `create_batch_runs` | `batch_runs` + partial unique `(batch_type, batch_date) where status='SUCCEEDED'` |

## 3. 번호 표 — 배정과 예약

레퍼런스 §7.3의 처음 계획에서 바뀐 곳이 있어서, **지금 배정(README·WBS·Jira 기준)**을 왼쪽에 적었어요. 예약은 팀 채팅이 정본이에요. 쓰기 전에 꼭 채팅에서 확인해 주세요. 〔제안〕 이 표를 노션에 "번호 · 파일 · 담당 · 상태(예약/PR/머지)" 한 장으로 옮겨서 예약 정본으로 쓰기.

| 번호 | 지금 배정 | 담당 | 언제 | 처음 계획 (레퍼런스 §7.3) |
|---|---|---|---|---|
| V004_1 | source — `source_files` + `transaction_id` 자동 발급 (안건 1, 10/30 #개발 합의) | 준혁 | 적재 전 | — |
| V005 | detection — `rules`(+ 룰 8개 행), `alerts`, `alert_transactions`, `alert_no_seq` | 준혁 | W2 | 같음 |
| V006 | cases — `cases`, `case_history`, `case_summaries`, `case_no_seq` | 지윤 | W2 | 같음 |
| V007 | `ai_call_logs` (`generation_id` 포함) | 진희 (SCRUM-18) | W2 | `create_alert_scores` |
| V008 | str — `str_drafts`, `str_revisions`, `verification_results` + `cases.current_draft_id` FK | 채영 | W2 | 같음 (W3) |
| V009 | `approvals` (`reason_code`, `narrative_sha256` 포함) | 채영 | W2 | 같음 (W4) |
| 미배정 | `alert_scores` | 준혁 | W3 | V007 |
| 미배정 | `access_logs` (진짜 로그인과 함께) | 진희 (SCRUM-19) | W2 | V010 (`create_log_tables`) |
| 미배정 | `batch_runs` 재실행 인덱스 좁히기 (안건 14·21) | 진희·준혁 | 점수 배치 전 | — |

〔확인 필요〕
- `alert_scores` 번호는 WBS에 "V0?? 번호 예약 필요 — V007은 ai_call_logs"로 남아 있어요.
- `V002`가 이미 들어가서, 안건 1의 `source_files`와 `transaction_id` 자동 발급은 **V004_1에서 ALTER**해요(10/30 #개발 합의). 기존 `transaction_id`는 identity가 아니라서 바꾸는 방법도 같이 정해야 해요.
- 〔미결 — 서식 회의 전〕 `str_reports`(확정 보고서 번호·마감·PDF), `CaseStatus`에 `REPORTED`, 룰 시드에 서식 유형 번호, `accounts.bank_country` — 서식 안건 1·2·5.

## 4. 테이블 목록 — 어느 파일에 어느 테이블 [레퍼런스 §1.1·§3]

| 묶음 | 테이블 | 베이스 | 추가만? |
|---|---|---|---|
| ① 원천 | `transactions`, `accounts`, `entities`, `customer_attributes` | 상속 없음 (`created_at`만) | 적재 후 불변 |
| ② 탐지 | `rules` | `BaseEntity` | — |
| | `alerts`, `alert_transactions`, `alert_scores` | `created_at`만 | `alerts`는 `score_status`·`case_opened`만 바뀜 |
| ③ 조사·보고 | `cases`, `str_drafts` | `BaseEntity` + `version` | — |
| | `case_history`, `case_summaries`, `str_revisions`, `verification_results`, `approvals` | `CreatedOnlyEntity` | **추가만** |
| ④ 운영·감사 | `users` | `BaseEntity` | — |
| | `access_logs`, `ai_call_logs`, `batch_runs` | `created_at`만 | `access_logs`·`ai_call_logs` 추가만 |

컬럼·인덱스 상세는 [레퍼런스 §3]에 있고, 담당자 초안에서 바뀌는 컬럼은 `06_features/`에 적어 뒀어요.
