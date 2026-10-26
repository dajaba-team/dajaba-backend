# ① 배치 체인 — 적재 → 룰 스캔 → 점수 → 케이스 생성

관리자가 버튼 네 번을 누르면 경보가 케이스가 된다. 네 배치는 전부 **`shared/batch`의 `BatchRun`** 에 기록되고(10/27 안건 14), 같은 날짜·유형을 두 번 돌리면 `409 BATCH_ALREADY_DONE`.

폴더 의존 방향 `source ← detection ← cases ← str` 그대로다. 케이스 배치가 detection을 부르지, detection이 cases를 부르지 않는다(지윤 문서 2절과 같음).

**ML 연동 보완 (2026-10-22, 준영)**: 아래 ③은 점수 파이프라인의 구현 기준이다. 성공 후 재실행 차단, 재시도 `batch_type`, 배치 요약 상태·건수는 공통 계약 확정 대상이다. 위의 중복 실행 설명만으로 점수 재시도까지 금지하지 않는다. [README의 공통 확인 항목](README.md#ml-연동에서-공통-확인이-필요한-항목)을 함께 본다.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as 관리자
    participant API as admin API<br/>(각 폴더 api/)
    participant BR as shared/batch<br/>BatchRun
    participant SRC as source<br/>LoadIbmDataset
    participant DET as detection<br/>RunRuleScan · RequestScores
    participant CAS as cases<br/>OpenCasesFromAlerts
    participant DB as PostgreSQL ops
    participant AI as AI 서버<br/>POST /v1/scores
    participant AIDB as PostgreSQL ai<br/>특징 · 예측 기록

    rect rgb(245,245,245)
    note over Admin,DB: ① 적재 — 유일하게 @Async (5~10분)
    Admin->>API: POST /admin/batches/load
    API->>BR: start(LOAD, 날짜 없음) → RUNNING
    BR-->>API: batch_run_id
    API-->>Admin: 202 Accepted {batch_run_id}
    API-)SRC: load() (비동기)
    SRC->>DB: COPY → staging → INSERT SELECT (4 테이블)
    SRC->>DB: 패턴 파일 → customer_attributes
    SRC->>DB: 검증 쿼리 5개 (expected.json 기준)
    SRC->>BR: finish(SUCCEEDED, rows_in, 검증 결과) / FAILED
    Admin->>API: GET /admin/batches/{id}
    API-->>Admin: {status, rows_in, error_message}
    end

    rect rgb(245,245,245)
    note over Admin,DB: ② 룰 스캔 — 동기, 한 트랜잭션 (1분 안쪽)
    Admin->>API: POST /admin/batches/rule-scan?date=2022-09-02
    API->>BR: start(RULE_SCAN, date) — 이미 SUCCEEDED면 409
    API->>DET: RunRuleScan(date)
    DET->>DB: 집계 네이티브 쿼리 (수신 계좌 × 일자 …)
    DET->>DET: FanInRule.evaluate(row) → AlertCandidate (룰 8개 같은 틀)
    DET->>DB: INSERT alerts (+ batch_run_id, alert_no) , alert_transactions
    DET->>BR: finish(SUCCEEDED, rows_in=거래 수, rows_out=경보 수)
    API-->>Admin: 200 {경보 수}
    end

    rect rgb(245,245,245)
    note over Admin,AIDB: ③ 점수 — 단일 AI 요청 최대 500개, 결과 저장은 묶음 단위
    Admin->>API: POST /admin/batches/score?date=
    API->>BR: start(SCORE, date)
    API->>DET: RequestScores(date)
    note over DET,AI: 점수 배치와 그 재시도 동안 model_version · feature_version 고정
    loop 최대 500개씩
        DET->>DB: score_status = PENDING 경보 조회
        DET->>AI: POST /v1/scores {alerts[…]}  (AiClient, 트랜잭션 밖)
        AI->>AI: 경보 ID · reference_ts · 대상 거래 · 버전 검증
        AI->>DB: SELECT 허용된 원천 거래 (기준 시점 이전, 정답 라벨 제외)
        AI->>AI: 학습과 같은 특징 함수 → 모델 추론 → 설명 · 근거 거래
        AI->>AIDB: 특징 · 예측 기록, 동일 입력 · 버전 재요청은 기존 prediction_id 재사용
        alt AI 응답 형식과 요청/응답 경보 ID 대응이 유효
            AI-->>DET: 200 {scores[…]} — 경보별 점수 · 품질 · 근거 · 버전
            loop 요청 경보별로 응답 대조
                alt 필수 결과 유효 · score 0~1 · 품질 FAILED 아님
                    DET->>DB: 성공 점수와 근거 저장, score_status = SCORED
                else score=null · 품질 FAILED · 점수/버전/근거 검증 실패
                    DET->>DB: 해당 경보 score_status = FAILED (0점 · LOW로 대체하지 않음)
                end
            end
        else HTTP 호출 실패 · 응답 해석 불가 · 경보 ID 누락/중복
            DET->>DB: 해당 묶음 score_status = FAILED, 기존 성공 묶음 유지
        end
        note right of DET: 실패 대상은 ④ 실패 경로로 재시도. 성공 결과의 저장 중복 방지 기준은 공통 계약 확인
    end
    DET->>BR: 성공 · 실패 건수 기록 (배치 요약 상태는 공통 계약 확정 대기)
    API-->>Admin: 200 {scored, failed}
    end

    rect rgb(245,245,245)
    note over Admin,DB: ④ 케이스 생성 — cases가 detection을 부른다 (반대 아님)
    Admin->>API: POST /admin/batches/open-cases?date=
    API->>BR: start(OPEN_CASES, date)
    API->>CAS: OpenCasesFromAlerts(date)
    CAS->>DET: 유스케이스 호출: 점수 있고 case_opened=false 인 경보 목록
    DET-->>CAS: List<AlertView> (ID + 점수)
    CAS->>CAS: CasePolicy.shouldOpen(score) — 기본 HIGH만
    CAS->>DB: INSERT cases (OPEN, case_no) , case_history(CREATED)
    CAS->>DET: MarkCaseOpened(alertId)  ← detection의 유스케이스
    DET->>DB: UPDATE alerts SET case_opened = true
    CAS->>BR: finish(SUCCEEDED, rows_out=케이스 수)
    API-->>Admin: 200 {케이스 수}
    end
```

## 읽을 때 같이 볼 것

| 질문 | 답 (그림 번호) |
|---|---|
| `batch_runs`는 누구 소속? (준혁) | `shared/batch`. 네 배치가 전부 같은 `BatchRun`을 쓴다 — ①~④의 `BR` 줄 |
| 적재만 `@Async`인가? (준혁) | 적재는 기존 설계대로 `202 + GET /batches/{id}` 폴링. 점수는 동기이며 단일 요청과 전체 배치 시간을 구분해 측정한다. 룰 스캔·케이스의 기존 실행 방식은 유지 |
| 케이스 배치가 detection을 부르는 방향 (지윤) | ④. `cases → detection` 유스케이스 두 개(경보 조회, `MarkCaseOpened`)만 부른다. detection은 cases를 모른다 |
| 점수 실패 묶음은? | ③ 안의 note → [④ 실패 경로](04_failure_paths.md) |
| 날짜 두 번 누르면? | 원문의 409 차단 정책은 공통 확인 대상. 점수 실패 대상 재시도와 새 유효 점수의 후속 케이스 생성을 막지 않는 재실행 기준을 별도로 확정 |

## ML 연동에서 확인할 것

- `500`은 단일 `/v1/scores` 요청의 상한이다. 전체 날짜의 점수 배치는 여러 요청을 포함하므로 전체 처리시간과 단일 요청 시간을 따로 측정한다.
- 입력은 경보 ID·대상 계좌·시간창·`reference_ts`·대상 거래 ID를 유지한다. 특징은 각 경보의 기준 시점 이전 관측으로 계산하고 학습과 같은 함수를 사용한다.
- 서비스 근거 거래는 서비스 HI-Small의 ID로 조회할 수 있어야 한다. 학습용 LI·SAML의 거래 ID를 서비스 응답에 넣지 않는다. 근거 범위는 확정된 경보 입력 계약을 따른다.
- 응답 형식과 요청/응답 ID의 대응을 먼저 검사한다. 경보 ID 누락·중복·알 수 없는 ID로 응답을 신뢰할 수 없으면 해당 묶음을 저장하지 않고 실패 처리한다. 대응이 유효한 응답은 경보별 점수·모델/특징 버전·품질·근거를 검사한다.
- 같은 경보·같은 입력·같은 모델/특징 버전의 재요청은 AI에 저장된 `prediction_id`와 결과를 재사용한다. HTTP 요청 시도 로그는 예측 결과와 구분해 남긴다.
- AI는 `ops.batch_runs`를 읽거나 수정하지 않는다. 허용된 원천 테이블을 조회하고 `ai` 스키마에 특징·예측을 기록한다. 공통 배치 관리와 케이스 생성은 백엔드 담당 영역이다.
