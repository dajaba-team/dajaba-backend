package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * AI 서버 요청·응답 DTO (규약 3·5·6절). Jackson SNAKE_CASE 라 @JsonProperty 없음.
 * 규약 v0.2(10/27 회의) 전이라 "원문은 JsonNode 로 통째로" 받는 최소 모양이다. 필드가 확정되면 record 를 채운다.
 * 백엔드는 응답을 풀지 않고 jsonb 로 저장하고(원칙 4), 필터·정렬에 쓰는 몇 개만 컬럼으로 뽑는다.
 */
public final class AiDtos {

    private AiDtos() {}

    /** POST /v1/scores 요청 한 건 (규약 2절 경보 JSON). 500 개씩 묶어 보낸다. */
    public record AlertPayload(String alertId, String accountKey, String entityId, String ruleId, String ruleVersion,
                               String typologyHint, String windowStart, String windowEnd, String referenceTs,
                               List<Long> transactionIds, JsonNode ruleFacts) {}

    /** POST /v1/scores 응답 (규약 3절). score_band 는 백엔드가 계산할 예정(안건 2) — 그때까지 AI 값 그대로. */
    public record ScoreResult(String alertId, Double score, String scoreBand, String dataQualityStatus,
                              String modelVersion, String predictionId, String producedAt, JsonNode raw) {}

    public record ScoresResponse(List<ScoreResult> scores) {
        public ScoresResponse { if (scores == null) scores = List.of(); }
    }

    /** POST /v1/str/drafts 요청 (규약 5.1). 케이스·경보·점수·고객·메모를 통째로. */
    public record StrDraftRequest(String caseId, JsonNode alert, JsonNode score, JsonNode entity, JsonNode customer,
                                  String investigatorNotes, JsonNode priorAlerts) {}

    /** POST /v1/str/drafts 응답 (규약 5.2). narrative 원문은 raw 에, 추출 컬럼 3개만 따로. */
    public record StrDraftResponse(String draftId, String generatorVersion, String producedAt, JsonNode facts,
                                   JsonNode narrative, JsonNode raw) {}

    /** POST /v1/str/verify 요청 (규약 6.1). */
    public record VerifyRequest(String draftId, JsonNode facts, JsonNode narrative) {}

    /** POST /v1/str/verify 응답 (규약 5.3). passed 정의는 안건 9 (코드 4 ∧ 평가자 5). */
    public record VerifyResponse(String verificationId, boolean passed, String verifierVersion, String producedAt, JsonNode raw) {}

    /** POST /v1/cases/summary */
    public record CaseSummaryRequest(String caseId, JsonNode alert, JsonNode score, JsonNode entity, JsonNode customer) {}

    public record CaseSummaryResponse(String summary, String generatorVersion, String producedAt, JsonNode raw) {}

    /** GET /v1/health */
    public record HealthResponse(String status, String modelVersion, String generatorVersion) {}
}
