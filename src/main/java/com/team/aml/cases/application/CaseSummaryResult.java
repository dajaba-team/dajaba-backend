package com.team.aml.cases.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.cases.domain.CaseSummary;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 요약 갱신 응답 (CASES 명세 API-12, 계약서 5.3 A7) — {@code { case_no, summary{…}, case_version }}.
 * 화면용 DTO 라 AI 원문(payload)·입력 스냅샷(account_key 가 들어 있음)은 내보내지 않는다.
 *
 * @param caseVersion 저장 뒤 새 cases.version — 화면이 다음 변경 요청에 그대로 싣는다
 */
public record CaseSummaryResult(String caseNo, Summary summary, long caseVersion) {

    /**
     * 요약 한 건의 화면 칸. API-13(현재 요약 조회)도 같은 모양을 쓴다.
     *
     * @param modelFocus "모델이 주목한 점" 한 줄(규약 §6.1 `model_focus`). 관측 사실(summary_lines)과 섞지 않으려고 따로 둔다. 없으면 null
     * @param isCurrent  지금 요약 입력이 이 요약을 만든 입력과 같은가. 판정할 수 없으면 null(API-13 — 재생성 안내)
     */
    public record Summary(List<String> summaryLines, String modelFocus, Instant producedAt, String generatorVersion,
                          long basedOnCaseVersion, Boolean isCurrent, String inputSha256) {

        static Summary of(CaseSummary s, Boolean isCurrent) {
            List<String> lines = new ArrayList<>();
            JsonNode node = s.getPayload().path("summary_lines");
            node.forEach(line -> lines.add(line.asText()));
            JsonNode focus = s.getPayload().path("model_focus");
            return new Summary(List.copyOf(lines), focus.isTextual() ? focus.asText() : null,
                    s.getProducedAt().truncatedTo(ChronoUnit.SECONDS),
                    s.getGeneratorVersion(), s.getBasedOnCaseVersion(), isCurrent, s.getInputSha256());
        }
    }
}
