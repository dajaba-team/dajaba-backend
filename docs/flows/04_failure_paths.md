# ④ 실패 경로 — AI 서버가 죽어 있을 때

두 장면. 공통 원칙: **AI 호출은 트랜잭션 밖이라 실패해도 DB가 반쯤 바뀐 상태가 없다**, **실패는 `ai_call_logs`와 상태 컬럼에 남고, 사람이 다시 누른다**. 시나리오 9(점수)와 7(초안)이 이 그림 그대로다.

## 4-1. 점수 배치 실패 → 재시도 (시나리오 9, 준혁)

점수·품질·예측 기록은 준영, 배치 실행과 운영 상태 저장은 준혁 담당이다. 아래는 ML 연동 보완안이다. 자동 재시도 횟수, 재시도 `batch_type`, 배치 요약 상태와 DB 중복 방지 제약은 [공통 확인 항목](README.md#ml-연동에서-공통-확인이-필요한-항목)에서 확정한다.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as 관리자
    participant API as detection api
    participant BR as shared BatchRun
    participant DET as RequestScores · RetryFailedScores
    participant AIC as shared AiClient<br/>(자동 재시도는 공통 계약)
    participant AI as AI 서버
    participant DB as PostgreSQL ops
    participant AIDB as PostgreSQL ai<br/>특징 · 예측 기록

    Admin->>API: POST /admin/batches/score?date=
    API->>BR: start(SCORE)
    API->>DET: RequestScores(date)
    DET->>DB: PENDING 경보 1,200건 조회
    note over DET,AI: 배치 · 재시도 동안 모델/특징 버전 고정, 요청 ID로 호출 추적
    loop 묶음 1 (500)
        DET->>AIC: scores(batch)
        AIC->>AI: POST /v1/scores
        AI->>AI: 경보별 특징 · 점수 · 설명 계산
        AI->>AIDB: 예측 기록 (동일 입력 · 버전이면 기존 prediction_id 재사용)
        AI-->>AIC: 200 {scores[…]} — 이 묶음은 전부 유효
        AIC-->>DET: 경보별 결과
        DET->>DET: 응답 경보 ID · 점수 · 품질 · 버전 · 근거 검증
        DET->>DB: alert_scores INSERT, score_status=SCORED  ⟵ 커밋
    end
    loop 묶음 2 (500) — 여기서 AI 서버 다운
        DET->>AIC: scores(batch)
        AIC->>AI: POST /v1/scores
        AI--xAIC: connection refused
        opt 공통 계약이 허용한 자동 재시도
            AIC->>AI: 동일 입력 · 버전으로 재요청
            AI--xAIC: 실패
        end
        AIC-->>DET: AiUnavailableException
        DET->>DB: score_status=FAILED (묶음 500건), ai_call_logs(FAILED)  ⟵ 커밋
        note right of DET: 묶음 1의 SCORED는 그대로. 롤백 없음
    end
    loop 묶음 3 (200)
        DET->>AIC: scores(batch)
        AI--xAIC: 실패
        DET->>DB: score_status=FAILED (200건)  ⟵ 커밋
    end
    DET->>BR: 성공 500 · 실패 700 기록 (배치 요약 상태는 공통 확정 대기)
    API-->>Admin: 200 {scored: 500, failed: 700}

    note over Admin,AI: — AI 서버 복구 —
    Admin->>API: POST /admin/batches/score/retry?date=
    API->>BR: 점수 재시도 시작 (batch_type · 실행 차단은 공통 확정 대기)
    API->>DET: RetryFailedScores(date)
    DET->>DB: score_status=FAILED 경보 700건
    DET->>AIC: scores(batch) × 2
    AIC->>AI: POST /v1/scores (실패 대상만, 고정된 버전)
    AI->>AIDB: 기존 예측 조회 또는 새 예측 저장
    AI-->>AIC: 200 {scores[…]} — 이 재시도는 전부 유효
    AIC-->>DET: 경보별 결과
    DET->>DET: 경보별 결과 재검증
    DET->>DB: 성공 결과 저장 · 중복 방지 (저장 키/제약 공통 확인), score_status=SCORED
    DET->>BR: 성공 700 · 실패 0 기록
    note over DET,AIDB: 동일 입력 · 모델/특징 버전 재요청은 같은 prediction_id와 결과. 새 모델 버전의 이력은 구분
```

**됐다 기준(시나리오 9)**: 실패 경보 `FAILED`, 성공 500·실패 700건 기록, 성공 묶음 유지, 복구 후 유효 결과를 받은 실패 대상만 `SCORED`. 같은 입력·버전의 재요청으로 유효 점수가 중복 저장되지 않고, 다른 모델 버전의 점수 이력은 구분한다.

### HTTP 200이어도 경보별로 실패할 수 있다

- `score=null` 또는 `data_quality_status=FAILED`인 경보는 `FAILED`다. 다른 경보의 유효 결과는 보존하고, 실패를 0점·LOW로 바꾸거나 케이스 생성 대상으로 보내지 않는다.
- 요청과 응답의 경보 ID, 점수 범위, 모델/특징 버전, 근거 거래 범위를 대조한다. 누락·중복 ID 등으로 응답을 신뢰할 수 없으면 해당 응답을 저장하지 않고 실패 처리한다.
- AI가 예측 저장을 끝낸 뒤 응답만 유실될 수 있다. 재요청 시 AI는 같은 입력·버전의 저장 결과(`prediction_id`, 점수, 설명, 근거, 생성 시각)를 재사용하고 요청 시도 로그를 별도로 남긴다.
- 품질 실패는 원인이 해소되지 않으면 재시도해도 다시 실패한다. 새 모델로 재평가하는 작업과 단순 통신 재시도를 섞지 않는다.
- ML 쪽에서는 단일 요청 일부 품질 실패, AI 저장 후 응답 유실, 같은 요청 반복, 모델/특징 버전 불일치를 추가 검증한다. 백엔드 저장 중복 방지와 연결하는 기준은 준혁·공통 담당과 확정한다.

## 4-2. 초안 요청 실패 (시나리오 7, 채영)

```mermaid
sequenceDiagram
    autonumber
    actor Inv as 조사역
    participant FE as 프론트
    participant STRC as StrDraftController
    participant REQ as RequestStrDraft<br/>(트랜잭션 없음)
    participant AIC as shared AiClient
    participant AI as AI 서버
    participant DB as PostgreSQL ops

    Inv->>FE: "초안 생성"
    FE->>STRC: POST /cases/{id}/str-drafts
    STRC->>REQ: RequestStrDraft(caseId)
    REQ->>DB: facts 읽기 (읽기만)
    REQ->>AIC: createDraft(facts)
    AIC->>AI: POST /v1/str/drafts (타임아웃 90초)
    alt 서버 다운
        AI--xAIC: connection refused (재시도 2회 후)
    else 90초 초과
        AI--xAIC: timeout
    else AI가 500/502 반환
        AI-->>AIC: 502 {error_code: LLM_INVALID_OUTPUT | LLM_BUDGET_EXCEEDED}
    end
    AIC->>DB: INSERT ai_call_logs (FAILED, error_code, 소요 ms)  ⟵ 별도 트랜잭션
    AIC-->>REQ: AiUnavailableException / AiRejectedException
    REQ-->>STRC: 예외
    STRC-->>FE: 502 {code: AI_UNAVAILABLE, message: "AI 서버 응답 없음. 잠시 후 다시 시도"}
    note over STRC,DB: SaveStrDraft는 호출되지 않음 → str_drafts 행 없음, 케이스는 ESCALATED 그대로
    FE-->>Inv: 실패 메시지 + "다시 시도" 버튼 (케이스 화면은 그대로)

    note over Inv,AI: — AI 서버 복구 —
    Inv->>FE: "다시 시도"
    FE->>STRC: POST /cases/{id}/str-drafts
    STRC->>REQ: RequestStrDraft
    REQ->>AIC: createDraft
    AIC->>AI: POST /v1/str/drafts
    AI-->>AIC: 200
    STRC->>DB: SaveStrDraft (③ 그림대로) → DRAFTED
    STRC-->>FE: 201
```

**됐다 기준(시나리오 7)**: 실패 메시지, 케이스 `ESCALATED` 유지, `ai_call_logs` 실패 행 1개, 서버 켜고 재시도 → 성공.

## 왜 이렇게 되나

| 설계 결정 | 그림에서 보이는 곳 |
|---|---|
| AI 호출은 트랜잭션 밖 (가이드 5절) | 4-2: `RequestStrDraft`가 DB를 안 바꾸니 실패해도 지울 게 없다 |
| 점수는 500개 묶음마다 커밋 (안건 10) | 4-1: 묶음 1 성공분이 묶음 2 실패에 영향 없음 |
| 점수 요청 자동 재시도 | 4-1: 횟수·대상 오류·시간 한도는 공통 `AiClient` 계약으로 확정. 4-2 STR 그림의 원문 횟수는 이번 ML 수정에서 변경하지 않음 |
| 점수 재시도의 저장 중복 방지 | AI는 동일 입력·모델/특징 버전의 `prediction_id`와 결과를 재사용. 백엔드 저장 키/제약은 공통 확인 대상이며 `alert_id` 하나로 모든 모델 버전 이력을 막지 않음 |
| 에러 코드는 백엔드가 매핑 (안건 9) | 4-2: AI 502 → 백엔드 `AI_UNAVAILABLE`, 프론트는 코드로 메시지 |
