# ⑥ 경보 하나의 일생 — 스윔레인 한 장

발표용·프론트 화면 흐름용. 다섯 줄(관리자 / 시스템 / AI / 조사역 / 승인자)에 경보 하나가 케이스가 되고 보고서가 승인되기까지. 세부는 ①~④ 그림으로.

```mermaid
flowchart TB
    subgraph ADM[관리자]
        A1[① 적재 버튼<br/>IBM CSV → ops] --> A2[② 룰 스캔 버튼<br/>날짜 선택] --> A3[③ 점수 버튼] --> A4[④ 케이스 생성 버튼]
    end

    subgraph SYS[시스템 · 백엔드]
        S1[(transactions<br/>accounts<br/>entities)] --> S2[룰 8종 평가<br/>→ alerts + 근거 거래]
        S2 --> S3[경보 500개씩<br/>점수 요청]
        S4[유효 점수 저장<br/>score_status=SCORED]
        S4 --> S5{CasePolicy<br/>HIGH?}
        S5 -- 아니오 --> S6[경보만 남음<br/>case_opened=false]
        S5 -- 예 --> S7[cases OPEN<br/>case_no 발급]
        SQ{경보별 결과 유효?<br/>점수 · 품질 · 버전 · 근거}
        SF[score_status=FAILED<br/>0점 · LOW로 대체하지 않음]
        SR[실패 대상만 점수 재시도<br/>기존 성공 결과 유지]
        S10[facts 계산<br/>금액·건수·날짜] --> S11[(str_drafts<br/>ACTIVE)]
        S11 --> S12[검증 결과 저장<br/>passed?]
        S13[승인 트랜잭션<br/>case APPROVED<br/>draft LOCKED<br/>approvals]
    end

    subgraph AI[AI 서버]
        I1["/v1/scores<br/>기준 시점 이전 특징 → 점수 · 설명 · 근거<br/>모델/특징 버전 · 품질 상태"]
        I2["/v1/cases/summary<br/>한 단락 요약"]
        I3["/v1/str/drafts<br/>6개 섹션 문장"]
        I4["/v1/str/verify<br/>코드 4 + 평가자 5"]
    end

    subgraph INV[조사역]
        V1[목록: 점수순] --> V2[상세: 경보·근거·이유<br/>+ 요약]
        V2 --> V3[내게 배정<br/>REVIEWING]
        V3 --> V4{판단}
        V4 -- 오탐 --> V5[종료 + 사유<br/>CLOSED]
        V4 -- 보고 --> V6[에스컬레이션<br/>ESCALATED]
        V6 --> V7[초안 생성 버튼<br/>DRAFTED]
        V7 --> V8[섹션 text 수정<br/>str_revisions]
        V8 --> V9[검증 버튼]
        V9 -- 실패 --> V8
        V9 -- 통과 --> V10[승인 요청<br/>PENDING_APPROVAL]
    end

    subgraph APR[승인자]
        P1[승인 대기 목록] --> P2{검토}
        P2 -- 반려 + 사유 --> P3[REJECTED]
        P2 -- 승인 --> P4[APPROVED · LOCKED]
    end

    A1 --> S1
    A2 --> S2
    A3 --> S3
    S3 --> I1 --> SQ
    SQ -- 유효 --> S4
    SQ -- 계산/품질 실패 또는 유효 결과 없음 --> SF
    SF -. 관리자 재시도 .-> SR
    SR --> S3
    A4 --> S5
    S7 --> V1
    V2 -. 요약 없으면 .-> I2 -.-> V2
    V7 --> S10 --> I3 --> S11
    V9 --> I4 --> S12
    S12 --> V9
    V10 --> P1
    P3 --> V8
    P2 -- 승인 --> S13 --> P4

    classDef ai fill:#efedff,stroke:#7030A0
    classDef end1 fill:#e5f4e4,stroke:#383
    classDef stop fill:#f0f0f0,stroke:#777
    class I1,I2,I3,I4 ai
    class P4 end1
    class V5,S6,SF stop
```

## ML 연동 보완 (2026-10-22, 준영)

- 계산 실패는 낮은 위험 점수가 아니다. 유효하게 계산된 `SCORED` 결과만 기존 `CasePolicy` 판단으로 보낸다. HIGH의 기준과 케이스 정책은 이 수정에서 변경하지 않는다.
- 실패 대상 재시도는 이미 성공한 경보를 다시 처리하지 않는다. 같은 입력·모델/특징 버전으로 AI에 재요청되면 기존 `prediction_id`와 결과를 재사용한다.
- AI는 서비스 원천에서 특징을 계산하고 `ai` 스키마에 특징·예측을 기록한다. 서비스 근거는 HI-Small 거래 ID를 사용한다. 상세는 [① 배치 체인](01_batch_chain.md), [④ 실패 경로](04_failure_paths.md).
- 재시도 후 새로 유효한 HIGH 경보가 생겼을 때의 추가 케이스 생성과 배치 실행 차단 정책은 공통 확인 대상이다. 이 그림에서 새로운 케이스 생성 정책을 확정하지 않는다.

## 화면과 상태의 대응 (프론트)

| 화면 | 상태 | 보이는 버튼 (역할) |
|---|---|---|
| 케이스 목록 | 전부 | 필터·정렬 (전원) / 배치 버튼 4개 (관리자만) |
| 케이스 상세 | OPEN | 내게 배정 (조사역) / 담당자 지정 (관리자) |
| 케이스 상세 | REVIEWING | 종료 · 에스컬레이션 · 메모 (담당 조사역만) / 담당자 교체 (관리자) |
| 케이스 상세 | ESCALATED | 초안 생성 (담당 조사역) |
| STR 편집 | DRAFTED · REJECTED | 섹션 수정 · 저장 · 검증 · 승인 요청(검증 통과 시만) · 초안 재생성 (담당 조사역) |
| STR 편집 (읽기 전용) | PENDING_APPROVAL | 없음 — 승인자 결정 대기 |
| 승인 | PENDING_APPROVAL | 승인 · 반려(사유 필수) (승인자, 담당 조사역 본인 제외) |
| 케이스 상세 (읽기 전용) | APPROVED · CLOSED | 없음. 이력만 |

케이스 상태 8개의 전이 규칙은 백엔드 설계 가이드 4절. 10/27 안건 11·15에서 종료·반려 전이가 추가될 수 있다.
