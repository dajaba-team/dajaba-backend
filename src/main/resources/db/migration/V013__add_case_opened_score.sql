-- V013: 케이스 생성 당시 점수·모델·정책 스냅샷 (SCRUM-236·237, CASES 명세 §7.1·§8.1, 미결 23).
-- 목록 점수 정렬(API-01, C4)은 이 칸으로 한다 — 11/15 결정 C안: cases 가 detection 테이블(ops.alert_scores)을
-- 조회 때 조인하지 않고, 케이스를 만들 때 점수를 복사해 둔다.
-- 근거: alert_scores 는 경보당 1행이고 app_backend 의 UPDATE·DELETE 가 없다(V005_1) — 한 번 저장한 점수는 바뀌지 않는다.
--       케이스는 SCORED 경보로만 만든다(FindAlertsForCaseOpening). 그래서 생성 당시 점수 = 지금 점수.
--       재채점(다른 모델로 다시 매기기)은 고도화라 MVP 에는 없다. 생기면 정렬 방식을 다시 정한다.
-- 칸은 모두 null 허용 — 이 파일 전에 만든 케이스는 정책 경계가 남아 있지 않다.
alter table ops.cases
    add column opened_score_id        bigint,                 -- ops.alert_scores.id. FK 없음(점수 행은 지우지 않는다)
    add column opened_score           numeric      check (opened_score >= 0 and opened_score <= 1),
    add column opened_model_version   varchar(64),
    add column opened_feature_version varchar(64),
    add column opened_policy_version  varchar(64),            -- 그때 쓴 CasePolicy (aml.case-policy.version)
    add column opened_low_to_medium   numeric,
    add column opened_medium_to_high  numeric,
    -- 점수 네 칸은 함께 있거나 함께 없다
    add constraint cases_opened_score_ck check (
        (opened_score_id is null) = (opened_score is null)
        and (opened_score is null) = (opened_model_version is null)
        and (opened_score is null) = (opened_feature_version is null)),
    -- 정책 세 칸도 함께. 정책은 점수가 있을 때만
    add constraint cases_opened_policy_ck check (
        (opened_policy_version is null) = (opened_low_to_medium is null)
        and (opened_policy_version is null) = (opened_medium_to_high is null)
        and (opened_policy_version is null or opened_score is not null));

-- 이미 만든 케이스는 점수만 채운다(한 번). 정책 경계는 그때 기록이 없어 null 로 둔다.
update ops.cases c
set opened_score_id        = s.id,
    opened_score           = s.score,
    opened_model_version   = s.model_version,
    opened_feature_version = s.feature_version
from ops.alert_scores s
where s.alert_id = c.alert_id;

-- 목록 기본 정렬 sort=score,desc — 점수 없는 케이스는 맨 뒤, 같은 점수는 created_at DESC, id DESC (명세 API-01).
create index cases_opened_score_idx on ops.cases (opened_score desc nulls last, created_at desc, id desc);
