package com.team.aml.detection.application;

import java.util.List;

/**
 * 점수 배치 한 번의 결과 (SD 명세 §6 응답).
 *
 * @param requested       AI 에 보낸 경보 수 (batch_runs.rows_in)
 * @param scored          SCORED 로 바뀐 경보 수 (rows_out)
 * @param failed          FAILED 로 남은 경보 수 — 항목 실패 + 묶음 실패
 * @param retryableFailed 그중 다시 보낼 것 — 코드 없음·COMPUTE_ERROR. 0 보다 크면 배치 FAILED
 * @param chunksTotal     AI 호출 묶음 수
 * @param chunksFailed    호출 자체가 실패한 묶음 수
 * @param modelVersions   응답 scoring_meta.model_version (중복 없이, 정렬)
 */
public record ScoreBatchSummary(int requested, int scored, int failed, int retryableFailed,
                                int chunksTotal, int chunksFailed, List<String> modelVersions) {

    public ScoreBatchSummary {
        modelVersions = List.copyOf(modelVersions);
    }

    /** batch_runs.error_message 에 남길 한 줄. 경보 내용 없이 건수만. */
    public String describe() {
        return "대상 " + requested + " · 점수 " + scored + " · 실패 " + failed + "(다시 보낼 것 " + retryableFailed + ")"
                + " · 묶음 " + chunksTotal + "(호출 실패 " + chunksFailed + ")";
    }
}
