package com.team.aml.shared.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * AI 서버 요청·응답 DTO (규약 v0.4 — SCRUM-151). Jackson SNAKE_CASE 라 @JsonProperty 없음(응답 record 의
 * `generationMeta` 만 `generation_meta` 를 명시로 매핑 — 중첩 뷰라 자동 명명이 애매함).
 *
 * 원칙 4 — 응답은 백엔드가 쓰는 칸만 record 필드로 뽑고 원문은 `raw: JsonNode` 로 둠. 규약이 바뀌어도
 * 두 리포가 같이 손대지 않도록. `raw` 는 `ai_call_logs` 에 저장하지 않음(메타데이터만, docs/03_shared §5).
 *
 * 섹션 이름은 String — `str.domain.StrSection` 을 shared 에서 참조하면 ArchitectureTest 위반. 섹션 집합
 * 검사는 str 도메인 몫.
 */
public final class AiDtos {

    private AiDtos() {}

    /** POST /v1/scores 요청 한 건 (규약 §2 경보 JSON). 500 개씩 묶어 보낸다. */
    public record AlertPayload(String alertId, String accountKey, String entityId, String ruleId, String ruleVersion,
                               String typologyHint, String windowStart, String windowEnd, String referenceTs,
                               List<Long> transactionIds, JsonNode ruleFacts, String createdAt) {}

    /** POST /v1/scores 요청 한 묶음 (규약 §2, ≤ max-batch 건). SCRUM-128. */
    public record ScoresRequest(long batchRunId, List<AlertPayload> alerts) {}

    /** POST /v1/scores 응답 맨 위 `scoring_meta` (규약 §3, AI `ScoringMeta`). 묶음마다 하나. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoringMeta(String modelVersion, String featureVersion, String serverVersion, Integer latencyMs) {}

    /**
     * POST /v1/scores 응답 항목 하나 + 그 항목이 온 묶음의 `scoring_meta`·`produced_at` (규약 §3, SCRUM-128).
     * `alert_scores` 한 행을 이 record 하나로 채운다. 같은 묶음 항목은 같은 `meta` 객체를 공유한다.
     *
     * - `score_band` 는 없다 — AI 가 주지 않고 cases `CasePolicy` 가 계산(안건 2).
     * - 항목 실패는 `dataQualityStatus="FAILED"` + `score`·`predictionId` null + `failureCode`(4값). SCORED/FAILED
     *   판단은 `dataQualityStatus` 로 [10/29 SCRUM-41].
     * - `reasons` 는 JsonNode — 저장·초안 요청에 그대로 넘기기만 함(원칙 4).
     * - `raw` 는 응답 항목 원문(`scoring_meta`·`produced_at` 없음).
     */
    public record ScoreResult(String alertId, String predictionId, Double score, double threshold,
                              String dataQualityStatus, String failureCode, JsonNode reasons,
                              List<Long> evidenceTransactionIds, List<Long> contextTransactionIds,
                              ScoringMeta meta, String producedAt, JsonNode raw) {

        /** `alert_scores.payload` — 항목 원문 + `scoring_meta` + `produced_at` (V005_1). raw 는 바꾸지 않는다. */
        public ObjectNode payloadForStorage() {
            ObjectNode out = raw instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
            ObjectNode m = out.putObject("scoring_meta");
            m.put("model_version", meta.modelVersion());
            m.put("feature_version", meta.featureVersion());
            m.put("server_version", meta.serverVersion());
            m.put("latency_ms", meta.latencyMs());
            out.put("produced_at", producedAt);
            return out;
        }
    }

    /** 모든 묶음의 항목을 합친 목록 (보낸 순서대로 묶음, 묶음 안은 응답 순서). */
    public record ScoresResponse(List<ScoreResult> scores) {
        public ScoresResponse { if (scores == null) scores = List.of(); }
    }

    /** narrative 한 섹션 (규약 §5.2·5.3). `section` 은 String — enum 참조 피함(ArchitectureTest). */
    public record NarrativeSectionDto(String section, String text, List<String> cites, List<Long> evidenceIds) {}

    /**
     * POST /v1/str/drafts 요청 (규약 §5.1). `draftId` 는 백엔드가 먼저 발급해 보냄.
     * SCRUM-151 — 칸마다 타입 있는 record.
     */
    public record StrDraftRequest(String draftId, String caseNo, AlertPayload alert,
                                  List<Long> evidenceTransactionIds, JsonNode reasons,
                                  Integer priorAlertCount, String investigatorNotes,
                                  String reportingInstitution) {}

    /**
     * POST /v1/str/drafts 응답 (규약 §5.2). 백엔드가 쓰는 칸만 record 필드 + 원문 `raw`.
     * `promptVersion` 은 `generation_meta.prompt_version` 을 꺼내 올림(SCRUM-151 — 응답 전체 record 는 안 함).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StrDraftResponse(String producedAt, String draftId, String generationId,
                                   List<NarrativeSectionDto> narrative,
                                   @JsonProperty("generation_meta") GenerationMetaView generationMeta,
                                   JsonNode facts, JsonNode verification, JsonNode raw) {
        /** `generation_meta.prompt_version` 만 뽑아 쓰기 위한 뷰 (다른 칸은 `raw` 로). */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record GenerationMetaView(String promptVersion) {}

        /** 편의 — `generation_meta.prompt_version` 바로 접근. null safe. */
        public String promptVersion() {
            return generationMeta != null ? generationMeta.promptVersion() : null;
        }
    }

    /**
     * POST /v1/str/verify 요청 (규약 §5.3). `draftId` 는 `AiClient.verify(draftId, request)` 의
     * 인자로 따로 넘어가므로 record 에 **안 넣음** — AI StrictModel 이 모르는 칸이면 422.
     */
    public record VerifyRequest(String generationId, Integer revisionNo,
                                List<NarrativeSectionDto> narrative) {}

    /** POST /v1/str/verify 응답 (규약 §5.3). 백엔드가 쓰는 칸만 + 원문 `raw`. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyResponse(String verificationId, String generationId, Integer revisionNo,
                                 String narrativeSha256, boolean passed, String status,
                                 String verifierVersion, String producedAt, JsonNode raw) {}

    /** POST /v1/cases/summary */
    public record CaseSummaryRequest(String caseId, JsonNode alert, JsonNode score, JsonNode entity, JsonNode customer) {}

    public record CaseSummaryResponse(String summary, String generatorVersion, String producedAt, JsonNode raw) {}

    /**
     * GET /v1/health (AI `main.py` `health()`, SCRUM-22 — 미결 28). 전부 String, 없으면 null.
     * `db` = "ok" | "down:<예외>", `llm` = "ok" | "missing_key". `generator_version` 은 AI 가 주지 않는다.
     */
    public record HealthResponse(String status, String db, String llm, String serverVersion,
                                 String modelVersion, String featureVersion, String retrievalVersion) {

        /** `status`·`db`·`llm` 이 모두 "ok" 인가 — null 도 ok 아님(칸이 빠진 옛 AI 서버도 드러나게). */
        public boolean allOk() {
            return "ok".equals(status) && "ok".equals(db) && "ok".equals(llm);
        }
    }
}
