-- V005_1: alert_scores — AI POST /v1/scores 응답 (레퍼런스 3.2, 안건 2·4·21, 미결 16·23).
-- 점수가 있는 경보(SCORED)만 한 행. FAILED 경보는 행을 만들지 않고 alerts.score_failure_code 에 남는다.
-- 경보당 1행이고 다른 모델로 다시 매기는 것은 고도화다. 모델별 예측 이력은 AI 쪽 ai.predictions 에 있다.
-- 등급(score_band)은 저장하지 않는다. HIGH/MEDIUM/LOW 는 cases 의 CasePolicy 가 계산한다.

create table ops.alert_scores (
    id               bigint       generated always as identity primary key,
    alert_id         bigint       not null unique references ops.alerts(id),
    -- 자릿수를 정하지 않아 AI 가 준 값을 반올림 없이 그대로 둔다. CasePolicy 경계 비교가 원래 값으로 된다.
    score            numeric      not null check (score >= 0 and score <= 1),
    threshold        numeric      not null check (threshold >= 0 and threshold <= 1),  -- AI 추천값. 운영 경계는 CasePolicy
    model_version    varchar(64)  not null,
    feature_version  varchar(64)  not null,
    prediction_id    varchar(64)  not null,               -- ai.predictions 와 잇는 값. FK 없음
    produced_at      timestamptz  not null,               -- AI 응답 시각 (시스템 시각, 미결 27)
    payload          jsonb        not null check (jsonb_typeof(payload) = 'object'),  -- 그 경보의 응답 항목 + scoring_meta
    created_at       timestamptz  not null default now()
);

-- 점수는 한 번 저장하면 바꾸지 않는다. app_ai 는 이 테이블에 접근하지 않는다.
revoke update, delete on ops.alert_scores from app_backend;
