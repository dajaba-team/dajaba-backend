package com.team.aml.detection.domain;

import com.team.aml.shared.ai.AiDtos.ScoreResult;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 점수 배치의 대상 조회·결과 저장 (SD 명세 §6). 경보는 score_status·score_failure_code 만 바꾼다(V005). */
public interface AlertScoreRepository {

    /** 같은 날짜 점수 배치 시작을 줄 세운다. 트랜잭션이 끝나면 풀린다. */
    void lockScoreStart();

    /**
     * 그 날짜의 점수 대상 — PENDING, 그리고 FAILED 중 score_failure_code 가 NULL·COMPUTE_ERROR.
     * 경보 id 가 afterId 보다 큰 것을 id 순으로 limit 개(다음 묶음은 마지막 id 뒤부터 — 이번 실행에서 실패한 것을 또 집지 않는다).
     */
    List<ScoreTarget> findScoreTargets(LocalDate date, long afterId, int limit);

    /** SCORED + alert_scores 한 행. 이미 SCORED 인 경보면 아무것도 바꾸지 않고 false. */
    boolean saveScore(long alertId, ScoreResult result, OffsetDateTime producedAt);

    /** FAILED + 코드(null 가능). 이미 SCORED 인 경보는 건드리지 않는다. */
    void markFailed(long alertId, String failureCode);

    /** 앱이 죽어 RUNNING 으로 남은 점수 배치를 FAILED 로. 바꾼 행 수. */
    int failRunningScoreBatches(String message);
}
