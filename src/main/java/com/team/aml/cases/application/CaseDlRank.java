package com.team.aml.cases.application;

import com.team.aml.shared.ai.AiDtos.DlFallbackReason;

import java.util.Objects;

/**
 * 케이스 한 건의 ML+DL 조사 순위 (SCRUM-420, 계약서 C1·C2 dl_rank). detection {@code GetDlRanking} 결과를 화면 모양으로.
 * <ul>
 *   <li>AVAILABLE — 그날 순위 전체가 경보·점수·기준 시각·근거와 다 맞음. ML 점수가 없는 경보는 두 순위가 null</li>
 *   <li>UNAVAILABLE — 하나라도 어긋났거나 AI 가 순위를 못 줌. 화면은 "순위 조회 불가". 순위 칸은 전부 null</li>
 * </ul>
 *
 * @param reason         UNAVAILABLE 일 때만 — detection {@code DlRankingResult.Reason} 이름
 *                       (AI_UNAVAILABLE·ALERT_SET_MISMATCH·RULE_MISMATCH·MODEL_MISMATCH·SCORE_MISMATCH·
 *                       REFERENCE_MISMATCH·EVIDENCE_MISMATCH). 화면은 값마다 나누지 않는다 — 로그·확인용
 * @param mlRank         ML 순위(1 이 최상위)
 * @param fusedRank      ML+DL 결합 순위(1 이 최상위)
 * @param fallbackReason null = ML·DL 둘 다 연결. INCOMPLETE_DL_EVIDENCE = ML 순위 자리 유지, ML_UNSCORED = 순위 없음
 * @param dlScore        DL 근거가 다 연결되지 않았으면 null
 */
public record CaseDlRank(Status status, String reason, Integer mlRank, Integer fusedRank,
                         DlFallbackReason fallbackReason, Double dlScore) {

    public enum Status { AVAILABLE, UNAVAILABLE }

    public CaseDlRank {
        Objects.requireNonNull(status, "status");
    }

    static CaseDlRank available(Integer mlRank, Integer fusedRank, DlFallbackReason fallbackReason, Double dlScore) {
        return new CaseDlRank(Status.AVAILABLE, null, mlRank, fusedRank, fallbackReason, dlScore);
    }

    /**
     * 순위를 붙이지 못한 경보(경보 번호를 못 찾음 등) — 엉뚱한 순위 대신 "조회 불가". 케이스 alert_id 는 FK·NOT NULL 이라
     * 실제로는 생기지 않는다. 그날 경보 집합과 응답이 어긋난 것과 같게 본다.
     */
    public static CaseDlRank orUnavailable(CaseDlRank rank) {
        return rank != null ? rank : unavailable("ALERT_SET_MISMATCH");
    }

    static CaseDlRank unavailable(String reason) {
        return new CaseDlRank(Status.UNAVAILABLE, Objects.requireNonNull(reason, "reason"), null, null, null, null);
    }
}
