# 05. Flyway 마이그레이션

> v0.2 · 2026-11-02 · §3 번호 재배정·상태 칸, §1 양보 규칙 (SCRUM-56) · 정리 진희
> v0.1 · 2026-10-29 · 초안(팀 확인 중)

DB 구조는 **Flyway SQL 파일만이 정답**이에요. JPA가 테이블을 만들지 않고, 누구도 DB 툴에서 직접 ALTER를 치지 않아요. 세 사람의 로컬 DB가 서로 달라지는 사고를 막는 유일한 방법이에요. [레퍼런스 §7]

## 1. 규칙 [레퍼런스 §7.1, README, PR 템플릿]

| 규칙 | 내용 |
|---|---|
| 위치 | `src/main/resources/db/migration/` |
| 파일 이름 | `V{3자리 번호}__{영어 snake_case 설명}.sql` (예: `V005__create_detection_tables.sql`) |
| 한 PR 한 파일 | PR에 마이그레이션은 최대 하나 |
| **번호 예약** | PR 열기 전에 팀 채팅에 "V0xx 쓰겠습니다" 한 줄. 같은 번호가 둘이면 Flyway가 기동을 거부해요. 예약하면 §3 표에도 한 줄(상태 `예약`) |
| **머지 순서** | 머지는 **번호 순서대로**(PR은 언제든 열어도 돼요). 낮은 번호가 나중에 들어오면 이미 높은 번호가 적용된 DB에서 Flyway가 기동을 거부해요 |
| 하위 번호 | **이미 예약된 번호 앞에 끼울 때만** `V004_1__…` 같은 하위 번호. Flyway는 `4.1`로 읽어서 V004와 V005 사이에 적용해요. 그 밖에는 다음 정수 번호 |
| **양보** | 하위 번호로 끼운 파일이 늦어져 뒤 번호 머지를 막으면 — 뒤 번호 PR이 승인됐는데 끼운 파일이 아직 머지 전이면 — **끼운 쪽이 다음 빈 번호로 옮겨요.** 머지 전이라 파일 이름만 바꾸면 돼요. 옮기면 팀 채팅과 §3 표도 같이 |
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

레퍼런스 §7.3의 처음 계획에서 바뀐 곳이 있어서, **지금 배정**을 왼쪽에 적었어요(11/2 SCRUM-56 재배정). 예약은 팀 채팅이 정본이고, 이 표는 그 현황판이에요. 쓰기 전에 꼭 채팅에서 확인하고, 상태가 바뀌면(예약 → PR → 머지) 이 표도 같이 고쳐 주세요.

| 번호 | 지금 배정 | 담당 | 언제 | 상태 | 처음 계획 (레퍼런스 §7.3) |
|---|---|---|---|---|---|
| V004_1 | source — `source_files` + `transaction_id` 자동 발급(`generated always as identity`) + `accounts.bank_country` (안건 1, 10/30 #개발 합의) | 준혁 | 적재 전 | PR #21 | — |
| V004_2 | `ai_call_logs` (`generation_id` 포함) | 진희 (SCRUM-18) | W2 | PR #9 | V010 (`create_log_tables`) |
| V004_3 | `access_logs` (진짜 로그인과 함께) | 진희 (SCRUM-19 B) | W2 | 머지됨 #27 | V010 (`create_log_tables`) |
| V005 | detection — `rules`, `alerts`, `alert_transactions`, `alert_no_seq`. 룰 행은 룰 명세(SCRUM-73) 확정 후 별도 파일 | 준혁 (SCRUM-72) | W3 | 머지됨 #31 | 같음 |
| V005_1 | `alert_scores` (경보당 1행, `score_band` 없음) | 준혁 (SCRUM-96) | W3 (11/3) | 머지됨 #36 | V007 |
| V005_2 | `transactions` 금액 `numeric(18,2)` → `numeric(20,6)` (11/2 준혁·준영 합의) | 준혁 (SCRUM-97) | W3 (11/3) | 머지됨 #34 | — |
| V006 | cases — `cases`, `case_history`, `case_summaries`, `case_no_seq` (`REPORTED` CHECK 포함) | 지윤 (SCRUM-48) | W3 | 머지됨 #39 | 같음 |
| V007 | **비움 — 쓰지 않아요** | — | — | 비움 | `create_alert_scores` |
| V008 | str — `str_draft_requests`, `str_drafts`, `str_revisions`, `verification_results`, `ops.str_draft_id_seq` + `cases.current_draft_id` FK | 채영 (SCRUM-31) | W2 | 머지됨 #28 | 같음 (W3) |
| V009 | `approvals` (`reason_code`, `narrative_sha256`, 그 초안의 수정본·검증 복합 FK, 초안당 승인 한 번) | 채영 (SCRUM-32) | W2 | 머지됨 #33 | 같음 (W4) |
| V010 | `case_history.from_assignee_id`·`to_assignee_id` + CHECK 두 개(배정·재배정 아닌 행은 빈칸 / 배정·재배정은 값 필수 — 예전 이력 때문에 `NOT VALID`). 예전 담당 자기 승인 막기 (미결 32) | 지윤 (W4) | W4 (11/10) | PR | — |
| 미배정 | `batch_runs` 재실행 인덱스 좁히기 (안건 14·21, 미결 4) | 진희·준혁 | 점수 배치 PR 직전 | 그때 다음 빈 번호 | — |
| 미배정 | `str_reports` (확정 보고서 번호·마감·PDF) | 채영 | 서식 회의 뒤 | 그때 다음 빈 번호 | — |

**머지 순서**: V004_1 → V004_2 → V004_3 → V005 → V005_1 → V005_2 → V006 → V008 → V009 → V010

- **왜 이 순서** — `ai_call_logs`·`access_logs`는 탐지·케이스 테이블을 참조하지 않아서 V005 앞으로 당겼어요. 진희 PR이 준혁님 V005·지윤님 V006을 기다리지 않아도 돼요. 거의 다 된 `ai_call_logs`를 앞(4_2), 아직 안 만든 `access_logs`를 뒤(4_3)로 둬서 준비된 것부터 들어가요. 진희 파일이 늦어 V005를 막으면 §1 양보 규칙대로 진희가 옮겨요.
- **`alert_scores`가 V005_1인 이유** — V005 바로 뒤라 채영님 V008·V009를 기다리지 않아요.
- **V007을 비운 이유** — 10/30 공지의 V007(`ai_call_logs`)이 V004_2로 갔어요. 뒤 번호를 당기면 V008·V009와 문서를 다 고쳐야 해서 비워 둬요. Flyway는 빈 번호가 있어도 괜찮아요.

〔확인 필요〕
- 〔미결 — 서식 회의 전〕 `str_reports` 번호, 룰 시드에 서식 유형 번호 — 서식 안건 1·2·5.
- `batch_runs` 재실행 인덱스 번호 — 점수 배치 PR 직전에 정해요(미결 4).

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
