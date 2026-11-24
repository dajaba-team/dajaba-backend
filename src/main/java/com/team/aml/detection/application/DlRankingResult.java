package com.team.aml.detection.application;

import com.team.aml.shared.ai.AiDtos.DlFallbackReason;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ML+DL 조사 순위 대조 결과 (SCRUM-376) — {@link GetDlRanking} 이 돌려준다. 예외 대신 이 타입으로.
 * 하나라도 어긋나면 {@link Unavailable} 하나 — 순위 일부만 쓰지 않는다.
 */
public sealed interface DlRankingResult {

    /** 쓸 수 없는 이유. 앞 단계부터 보고 처음 어긋난 하나. */
    enum Reason {
        /** AI 호출이 던진 예외 전부(503 `DL_RANKING_UNAVAILABLE`·연결 실패·타임아웃·응답 확인 실패 502 등). */
        AI_UNAVAILABLE,
        /** 그날 `alert_no` 집합 ≠ 응답 `alert_id` 집합(크기·원소). 처음 스캔이 아니라 번호가 밀린 경우 포함. */
        ALERT_SET_MISMATCH,
        /** 경보별 `rule_id` 다름. */
        RULE_MISMATCH,
        /** DB 점수가 있는 경보의 `model_version` ≠ 응답 `ml_model`. */
        MODEL_MISMATCH,
        /** 점수 차 > 1e-6, 또는 DB 점수·응답 `ml_score` 중 한쪽만 있음. */
        SCORE_MISMATCH,
        /** 경보별 DB `reference_ts`(`yyyy-MM-dd'T'HH:mm:ss`, 초 아래 버림) ≠ 응답 `reference_ts`. SCRUM-415. */
        REFERENCE_MISMATCH,
        /** 경보별 DB 근거 원본 행으로 계산한 digest ≠ 응답 `evidence_digest`. SCRUM-415. */
        EVIDENCE_MISMATCH
    }

    /**
     * 경보 하나의 순위.
     *
     * @param mlRank         `ML_UNSCORED` 면 null
     * @param fusedRank      `ML_UNSCORED` 면 null
     * @param fallbackReason null = ML·DL 둘 다 연결
     * @param dlScore        DL 근거가 다 연결되지 않았으면 null
     */
    record DlRank(Integer mlRank, Integer fusedRank, DlFallbackReason fallbackReason, Double dlScore) {
    }

    /**
     * 쓸 수 있는 순위.
     *
     * @param ranks 경보 번호 → 순위, 그날 경보 전부. 순서 = 응답 순서(fused 순, 그 뒤 ML 점수 없는 경보). 바꿀 수 없음
     */
    record Available(LocalDate scanDate, String snapshotSha256, String mlModel, String dlModel,
                     Map<String, DlRank> ranks) implements DlRankingResult {
        public Available {
            ranks = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(ranks, "ranks")));
        }
    }

    /** 쓸 수 없음 — 화면은 "순위 조회 불가". */
    record Unavailable(Reason reason) implements DlRankingResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
