package com.team.aml.shared.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
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
     * - 항목 실패는 `dataQualityStatus="FAILED"` + `score`·`predictionId` null + `failureCode`(5값). SCORED/FAILED
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

    /**
     * POST /v1/cases/summary 요청 (규약 v0.4 §6.1, 11/6 — AI SCRUM-129). 요약도 초안과 같은 facts 를 쓰므로
     * `reasons`·`priorAlertCount` 는 초안 요청(§5.1)과 같은 값이고 필수다. `reportingInstitution` 은 선택(null 이면
     * AI 가 거래자 계좌 은행명). 메모(`investigator_notes`)는 넣지 않는다 — 요약 입력에 없음(CASES 명세 C6).
     */
    public record CaseSummaryRequest(String caseNo, AlertPayload alert, List<Long> evidenceTransactionIds,
                                     JsonNode reasons, Integer priorAlertCount, String reportingInstitution) {}

    /**
     * POST /v1/cases/summary 응답 (규약 v0.4 §6.1). 백엔드가 쓰는 칸만 record 필드 + 원문 `raw`.
     * 코드 검사가 실패해도 AI 는 200 을 주고 `code_checks[].match=false` 로 알린다 — 저장 여부는 cases 가 정한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseSummaryResponse(String producedAt, String caseNo, String generationId,
                                      List<String> summaryLines, JsonNode codeChecks,
                                      @JsonProperty("generation_meta") StrDraftResponse.GenerationMetaView generationMeta,
                                      JsonNode raw) {
        /** `generation_meta.prompt_version` — `case_summaries.generator_version`·`ai_call_logs.ai_version`. null safe. */
        public String promptVersion() {
            return generationMeta != null ? generationMeta.promptVersion() : null;
        }
    }

    /**
     * POST /v1/cases/ask 요청 (규약 5.4, SCRUM-291) = 요약 요청 6칸(이름·값·필수 여부 같음) + `question`.
     * Java record 는 상속이 없어 칸을 다시 적었다 — 요약 조립을 그대로 쓰려면 {@link #of}. `question` 은
     * **앞뒤 공백을 자르지 않고** 그대로 보낸다(길이·공백 검사는 유스케이스 400 몫, AI 와 같은 단위).
     */
    public record AskRequest(String caseNo, AlertPayload alert, List<Long> evidenceTransactionIds,
                             JsonNode reasons, Integer priorAlertCount, String reportingInstitution,
                             String question) {

        public static AskRequest of(CaseSummaryRequest summary, String question) {
            return new AskRequest(summary.caseNo(), summary.alert(), summary.evidenceTransactionIds(),
                    summary.reasons(), summary.priorAlertCount(), summary.reportingInstitution(), question);
        }
    }

    /** 질문 답변 상태 (규약 5.4). 넷 다 AI 200. 모르는 값은 `requireAskResponse` 가 먼저 거부한다. */
    public enum AskStatus { ANSWERED, NO_DATA, UNVERIFIED, OUT_OF_SCOPE }

    /** 질문 종류 — `OUT_OF_SCOPE` 면 null. */
    public enum AskKind { FACT, WHY }

    /** 범위 밖 사유 — `OUT_OF_SCOPE` 일 때만 값. */
    public enum OutOfScopeReason { EDIT_REQUEST, OUTSIDE_CASE, JUDGMENT }

    /**
     * 질문 답변 `generation_meta` (AI `AskGenerationMeta`). 토큰·지연은 계획+답 두 호출 합계.
     * `promptVersion` 은 답 프롬프트 — `OUT_OF_SCOPE`(답 호출 안 함)만 null. `bundles` 는 OUT_OF_SCOPE 면 [].
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AskGenerationMeta(String llmModel, String promptVersion, String planPromptVersion,
                                    List<String> bundles, String serverVersion, Integer tokensIn,
                                    Integer tokensOut, Integer latencyMs) {}

    /**
     * POST /v1/cases/ask 응답 (규약 5.4). 칸 전부 + 원문 `raw`. 상태별 칸 규칙은 `WebClientAiClient.requireAskResponse`
     * 가 record 로 바꾸기 전에 확인한다. `codeChecks` 는 요약처럼 JsonNode(저장 모양은 cases 몫).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AskResponse(String producedAt, String caseNo, String generationId, AskStatus status,
                              AskKind kind, OutOfScopeReason outOfScopeReason, String answer,
                              List<String> cites, List<Long> evidenceIds, JsonNode codeChecks,
                              @JsonProperty("generation_meta") AskGenerationMeta generationMeta,
                              JsonNode raw) {}

    /**
     * POST /v1/references/search 요청 (규약 5.5, SCRUM-309). 유형·출처는 문자열 — shared 가 detection 유형 enum 을
     * 참조하지 않는다(잘못된 값은 AI 400 → `AI_INVALID_REQUEST`). `query` 는 유스케이스가 앞뒤 공백을 지우고 빈 값이면
     * null 로 넘기고, `AiClient` 는 받은 값 그대로 보낸다(trim·길이 재검사 안 함). 초안 번호는 본문에 없다(인자로 따로).
     */
    public record ReferenceSearchRequest(List<String> typologies, String query, int topK, List<String> sourceTypes) {}

    /** 참고 사례 검색 상태 (규약 5.5). 둘 다 AI 200. 모르는 값은 `requireReferenceSearchResponse` 가 먼저 거부한다. */
    public enum ReferenceSearchStatus { FOUND, EMPTY }

    /**
     * 참고 사례 한 건 (AI `ReferenceResult`). 원문 본문은 제외하고 관련 구절 `excerpt` 만 받는다(최대 300 UTF-16 코드 유닛, 없으면 null).
     * `typologies` 는 그 청크 태그 전부라 검색 가능한 5종 밖 유형도 올 수 있어 문자열. `publisher`·`year` 는 없으면 null.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferenceResult(String docId, String docTitle, String publisher, Integer year, int page,
                                  double score, String chunkId, List<String> typologies, String sourceType,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String excerpt) {
        public ReferenceResult(String docId, String docTitle, String publisher, Integer year, int page,
                               double score, String chunkId, List<String> typologies, String sourceType) {
            this(docId, docTitle, publisher, year, page, score, chunkId, typologies, sourceType, null);
        }
    }

    /** 참고 사례 검색 `search_meta` (AI `ReferenceSearchMeta`). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferenceSearchMeta(boolean degraded, int candidates, int latencyMs) {}

    /**
     * POST /v1/references/search 응답 (규약 5.5). 칸 전부 + 원문 `raw`. 규칙은 `WebClientAiClient.requireReferenceSearchResponse`
     * 가 record 로 바꾸기 전에 확인한다. 저장하지 않는 응답이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferenceSearchResponse(String producedAt, ReferenceSearchStatus status, List<ReferenceResult> results,
                                          String retrievalVersion, ReferenceSearchMeta searchMeta, JsonNode raw) {}

    /**
     * POST /v1/references/str 요청 — 승인 STR 색인 (AI SCRUM-310, SCRUM-357). `draftId` 는 승인된 초안 번호(`DRF-…`) —
     * AI 가 그 초안 facts 로 케이스 계좌를 대조하고, `ai_call_logs.target_id` 에도 이 값이 간다. `approvedAt` 은 UTC `Z`.
     * 유형·섹션 이름은 문자열(검색과 같음). 모양 검사(번호 형식·유형 0~3·섹션 한 세트·글 1~1200)는 AI 가 한다(틀리면 400).
     */
    public record StrIndexRequest(String reportNo, String draftId, String approvedAt, List<String> typologies,
                                  List<StrIndexSection> sections) {}

    /** 색인할 섹션 한 개 — 7항목 또는 옛 6섹션 이름 + 글(자르지 않고 그대로). */
    public record StrIndexSection(String section, String text) {}

    /** 승인 STR 색인 상태. 둘 다 AI 200. 모르는 값은 `requireStrIndexResponse` 가 먼저 거부한다. */
    public enum StrIndexStatus { INDEXED, SKIPPED }

    /**
     * 건너뛴 이유 (AI `StrIndexSkipReason`). 계좌 원문 · 공백 뺀 글 합 100자 미만 · AI 스위치 꺼짐 · 그 초안 facts 가 AI 에
     * 없음(목업 모드로 만든 초안 등). 모르는 값은 `requireStrIndexResponse` 가 먼저 거부한다.
     */
    public enum StrIndexSkipReason { RAW_ACCOUNT, TOO_SHORT, DISABLED, NO_FACTS }

    /**
     * POST /v1/references/str 응답. 칸 전부 + 원문 `raw`. `skipReason` 은 INDEXED 면 null, `retrievalVersion` 은 SKIPPED
     * (특히 DISABLED)면 null 일 수 있다. 규칙은 `WebClientAiClient.requireStrIndexResponse` 가 record 로 바꾸기 전에 확인.
     * 저장하지 않는 응답이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StrIndexResponse(String producedAt, String reportNo, StrIndexStatus status,
                                   StrIndexSkipReason skipReason, int chunkCount, String retrievalVersion,
                                   JsonNode raw) {}

    /**
     * ML+DL 조사 순위에서 결합 순위를 못 쓴 이유 (AI `dl_ranking.py` `RankedAlert.fallback_reason`, SCRUM-376).
     * null = ML·DL 둘 다 연결. 모르는 값은 `requireDlRankingResponse` 가 먼저 거부한다.
     */
    public enum DlFallbackReason {
        /** DL 근거가 하나라도 빠짐 — ML 순위 자리 유지, `dl_score` null. */
        INCOMPLETE_DL_EVIDENCE,
        /** ML 점수 없음 — 두 순위 null, 배열 맨 뒤. DL 근거가 다 있으면 `dl_score` 는 올 수 있다. */
        ML_UNSCORED
    }

    /**
     * 조사 순위 한 건 (AI `RankedAlert`). `alertId` 는 **경보 번호**(`ALT-YYYYMMDD-NNNNNN`, DB 숫자 id 아님).
     * 순위는 1이 최상위, ML 점수가 있는 경보 안에서 1…k.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DlRankedAlert(String alertId, String ruleId, Double mlScore, String mlFailureCode, Double dlScore,
                                int evidenceCount, int dlLinked, DlFallbackReason fallbackReason, Integer mlRank,
                                Integer fusedRank, String referenceTs, String evidenceDigest) {}

    /**
     * GET /v1/dl-rankings/{scan_date} 응답 (규약 6절 v0.4, AI `ReviewRanking`, SCRUM-376). 스캔 하루치 경보 전부,
     * 배열 순서 = `fused_rank` 순 다음 ML 점수 없는 경보. 규칙은 `WebClientAiClient.requireDlRankingResponse` 가 record 로
     * 바꾸기 전에 확인한다. 저장하지 않는 응답이고 칸이 전부 record 에 있어 `raw` 는 두지 않는다(원칙 4 의 예외).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DlRankingResponse(LocalDate scanDate, String snapshotSha256, String mlModel, String dlModel,
                                    int evidenceTransactions, int scoredTransactions, int linkedAlerts,
                                    int mlOnlyAlerts, int unscoredAlerts, List<DlRankedAlert> alerts) {
        public DlRankingResponse { alerts = alerts == null ? List.of() : List.copyOf(alerts); }
    }

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
