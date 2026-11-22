package com.team.aml.shared.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 서버 없이 개발할 때 쓰는 고정 응답 (aml.ai.mock=true — local/test 프로파일 기본).
 * 점수는 alert_id 해시로 0.05~0.95 사이를 결정적으로 돌려준다 → 같은 경보는 항상 같은 점수.
 * 초안·검증·요약은 규약 §5 모양의 최소 JSON. 진짜 서버를 붙이려면 aml.ai.mock=false + AI_BASE_URL.
 *
 * SCRUM-151: 초안 응답은 v2 7항목(SUBJECT·DATE·PLACE·INSTRUMENT·METHOD·GROUNDS·OPINION), 섹션마다
 * `cites` 1개 이상, `draft_id` 는 요청 값 그대로, `generation_meta.prompt_version="narrative_v2"`.
 */
@Component
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "true")
public class MockAiClient implements AiClient {

    /** v2 7항목 순서 — 규약 §5.2. shared 수준 String(StrSection enum 참조 금지, ArchitectureTest). */
    private static final List<String> V2_SECTIONS = List.of(
            "SUBJECT", "DATE", "PLACE", "INSTRUMENT", "METHOD", "GROUNDS", "OPINION");
    /** 해시용 기본 ObjectMapper — 앱 설정(들여쓰기 등)이 해시에 끼지 않게 NarrativeHash 처럼 새로 만든다. */
    private static final ObjectMapper HASH_JSON = new ObjectMapper();
    /** 해시 단락 순서 — StrSection 선언 순서(V1 6섹션 → V2 7항목). 검증 목업 해시용. StrSection 바꾸면 여기도 */
    private static final List<String> SECTION_ORDER = List.of(
            "SUMMARY", "WHO", "WHAT_WHEN_WHERE", "HOW", "WHY_SUSPICIOUS", "CONCLUSION",
            "SUBJECT", "DATE", "PLACE", "INSTRUMENT", "METHOD", "GROUNDS", "OPINION");
    /** 섹션마다 cites 1개 이상 — 채영 cites 저장 테스트 재료 (SCRUM-151). */
    private static final List<String> SECTION_CITES = List.of(
            "subject", "period", "subject.account_key", "payment_formats",
            "counterparties.count", "observations", "typology");

    /** 목업 응답의 추천 임계값 — AI 예시(`score_response.json`)와 같은 값. 운영 경계는 CasePolicy. */
    private static final double MOCK_THRESHOLD = 0.7;

    private final ObjectMapper om;

    public MockAiClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public ScoresResponse scores(long batchRunId, List<AlertPayload> alerts) {
        // SCRUM-128: 규약 §3 모양 — 묶음 하나로 보고 scoring_meta·produced_at 을 항목마다 붙임. 항목은 전부 OK.
        ScoringMeta meta = new ScoringMeta("mock-0.0.1", "mock-f0", "mock", 0);
        String producedAt = Instant.now().toString();
        List<ScoreResult> out = new ArrayList<>();
        for (AlertPayload a : alerts) {
            double score = 0.05 + (Math.abs(a.alertId().hashCode()) % 90) / 100.0;   // 0.05 ~ 0.94
            String predictionId = "PRD-mock-" + Math.abs(a.alertId().hashCode());
            List<Long> evidence = a.transactionIds() == null ? List.of() : List.copyOf(a.transactionIds());
            ObjectNode raw = om.createObjectNode()
                    .put("alert_id", a.alertId()).put("prediction_id", predictionId)
                    .put("score", score).put("threshold", MOCK_THRESHOLD)
                    .put("data_quality_status", "OK");
            raw.putNull("failure_code");
            raw.putArray("reasons");
            evidence.forEach(raw.putArray("evidence_transaction_ids")::add);
            raw.putArray("context_transaction_ids");
            out.add(new ScoreResult(a.alertId(), predictionId, score, MOCK_THRESHOLD, "OK", null,
                    raw.get("reasons"), evidence, List.of(), meta, producedAt, raw));
        }
        return new ScoresResponse(out);
    }

    @Override
    public StrDraftResponse createDraft(StrDraftRequest request) {
        String draftId = request.draftId();   // SCRUM-151: 요청 값 그대로
        String generationId = "GEN-mock-" + UUID.randomUUID().toString().substring(0, 6);
        String producedAt = Instant.now().toString();

        List<NarrativeSectionDto> narrative = new ArrayList<>();
        for (int i = 0; i < V2_SECTIONS.size(); i++) {
            String section = V2_SECTIONS.get(i);
            narrative.add(new NarrativeSectionDto(
                    section,
                    "(목업) " + section + " 섹션 문장",
                    List.of(SECTION_CITES.get(i)),
                    List.of()));
        }

        ObjectNode generationMeta = om.createObjectNode()
                .put("llm_model", "mock-0.0.1")
                .put("prompt_version", "narrative_v2");
        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("draft_id", draftId)
                .put("generation_id", generationId);
        raw.set("facts", om.createObjectNode());
        raw.set("narrative", om.valueToTree(narrative));
        raw.set("generation_meta", generationMeta);
        raw.putNull("verification");

        return new StrDraftResponse(
                producedAt, draftId, generationId, narrative,
                new StrDraftResponse.GenerationMetaView("narrative_v2"),
                om.createObjectNode(), null, raw);
    }

    @Override
    public VerifyResponse verify(String draftId, VerifyRequest request) {
        String verificationId = "VRF-mock-" + UUID.randomUUID().toString().substring(0, 6);
        String producedAt = Instant.now().toString();
        ObjectNode raw = om.createObjectNode()
                .put("verification_id", verificationId)
                .put("generation_id", request.generationId())
                .put("revision_no", request.revisionNo() == null ? 1 : request.revisionNo())
                .put("narrative_sha256", narrativeSha256(request.narrative()))
                .put("passed", true)
                .put("status", "PASSED")
                .put("verifier_version", "mock-0.0.1")
                .put("produced_at", producedAt);
        raw.putArray("code_checks");
        raw.putArray("llm_checks");
        raw.putNull("narrative_refs");
        raw.putNull("judge_meta");

        return new VerifyResponse(
                verificationId, request.generationId(),
                request.revisionNo() == null ? 1 : request.revisionNo(),
                raw.path("narrative_sha256").asText(), true, "PASSED", "mock-0.0.1", producedAt, raw);
    }

    /**
     * 진짜 AI 처럼 받은 본문의 해시를 돌려준다 — 고정값("0"×64)이면 GetStrDraft is_current·ApprovalGate 가 늘 "다른 본문"으로
     * 봐서, 다시 검증해도 "이전 수정본을 검증한 결과"가 뜨고 승인 요청이 막혔다.
     * 규칙은 str.domain.NarrativeHash 와 같다(단락 순서 = StrSection 선언 순서, section·text 만, NFC, 공백 없는 JSON,
     * 제어문자 \\u 소문자, UTF-8 SHA-256). shared 는 str 을 모르니(ArchitectureTest) 여기 따로 두고, 같은 값인지는
     * str 쪽 MockAiVerifyHashTest 가 확인한다. 목업이라 세트 검사(V1·V2 한 세트)는 하지 않는다.
     */
    String narrativeSha256(List<NarrativeSectionDto> narrative) {
        ArrayNode array = HASH_JSON.createArrayNode();
        narrative.stream()
                .sorted(Comparator.comparingInt(MockAiClient::sectionOrder))
                .forEach(n -> array.addObject()
                        .put("section", n.section())
                        .put("text", Normalizer.normalize(n.text(), Normalizer.Form.NFC)));
        try {
            byte[] json = HASH_JSON.writer().with(new LowercaseHexEscapes()).writeValueAsBytes(array);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("목업 본문 해시를 만들 수 없음", e);
        }
    }

    /** StrSection 선언 순서(V1 6개 → V2 7개). 모르는 이름은 맨 뒤. */
    private static int sectionOrder(NarrativeSectionDto n) {
        int i = SECTION_ORDER.indexOf(n.section());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** NarrativeHash.LowercaseHexEscapes 와 같다 — 제어문자를 \\u001f 처럼 소문자로(Python json.dumps 와 같게). */
    private static final class LowercaseHexEscapes extends CharacterEscapes {
        private final int[] asciiEscapes = standardAsciiEscapesForJSON();

        LowercaseHexEscapes() {
            for (int c = 0; c < 0x20; c++) {
                if (asciiEscapes[c] == ESCAPE_STANDARD) {
                    asciiEscapes[c] = ESCAPE_CUSTOM;
                }
            }
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return ch >= 0x20 ? null : new SerializedString(String.format("\\u%04x", ch));
        }
    }

    @Override
    public CaseSummaryResponse caseSummary(CaseSummaryRequest request) {
        // 규약 v0.4 §6.1 응답 모양. model_focus 는 문자열 한 줄(또는 null), 코드 검사는 4종 전부 통과로 둔다(목업).
        String producedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String generationId = "GEN-mock-" + UUID.randomUUID().toString().substring(0, 6);
        List<String> lines = List.of("(목업) 이 경보는 단기간 다수 계좌에서 입금이 집중된 유형입니다.",
                "(목업) 근거 거래 " + (request.evidenceTransactionIds() == null ? 0 : request.evidenceTransactionIds().size())
                        + "건을 확인해 주세요.");
        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("case_no", request.caseNo())
                .put("generation_id", generationId);
        raw.putObject("facts");
        lines.forEach(raw.putArray("summary_lines")::add);
        raw.put("model_focus", "(목업) 모델은 account_in_counterparties_24h 신호를 가장 크게 봄");
        com.fasterxml.jackson.databind.node.ArrayNode checks = raw.putArray("code_checks");
        for (String check : List.of("AMOUNT_MATCH", "DATE_MATCH", "COUNT_MATCH", "ACCOUNT_ID_MATCH")) {
            checks.addObject().put("check", check).putNull("section")
                    .put("draft_value", "(목업)").put("source_value", "(목업)").put("match", true);
        }
        raw.putObject("generation_meta").put("prompt_version", "mock-0.0.1");
        return new CaseSummaryResponse(producedAt, request.caseNo(), generationId, lines,
                raw.get("code_checks"), new StrDraftResponse.GenerationMetaView("mock-0.0.1"), raw);
    }

    /**
     * 질문 낱말 → (상태, kind, 사유). 위에서부터 처음 맞는 것, 없으면 ANSWERED·FACT (SCRUM-291 — 화면 4종을 목업으로 보게).
     * 예시 JSON(`contracts/examples`)은 리포 루트라 실행 파일에 없어 읽지 않는다.
     */
    private record AskRule(String word, AskStatus status, AskKind kind, OutOfScopeReason reason) {}

    private static final List<AskRule> ASK_RULES = List.of(
            new AskRule("수정", AskStatus.OUT_OF_SCOPE, null, OutOfScopeReason.EDIT_REQUEST),
            new AskRule("다른 케이스", AskStatus.OUT_OF_SCOPE, null, OutOfScopeReason.OUTSIDE_CASE),
            new AskRule("판단", AskStatus.OUT_OF_SCOPE, null, OutOfScopeReason.JUDGMENT),
            new AskRule("없는", AskStatus.NO_DATA, AskKind.FACT, null),
            new AskRule("불일치", AskStatus.UNVERIFIED, AskKind.FACT, null),
            new AskRule("왜", AskStatus.ANSWERED, AskKind.WHY, null));

    @Override
    public AskResponse ask(AskRequest request) {
        String question = request.question() == null ? "" : request.question();
        AskRule rule = ASK_RULES.stream().filter(r -> question.contains(r.word())).findFirst()
                .orElse(new AskRule("", AskStatus.ANSWERED, AskKind.FACT, null));
        boolean answered = rule.status() == AskStatus.ANSWERED;
        boolean outOfScope = rule.status() == AskStatus.OUT_OF_SCOPE;

        String producedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String generationId = "g-mock" + UUID.randomUUID().toString().substring(0, 4);
        List<Long> sentEvidence = request.evidenceTransactionIds() == null ? List.of() : request.evidenceTransactionIds();
        String answer = answered ? "(목업) 입금 상대 계좌는 3개예요." : null;
        List<String> cites = answered ? List.of("counterparties.count") : List.of();
        List<Long> evidenceIds = answered && !sentEvidence.isEmpty() ? List.of(sentEvidence.get(0)) : List.of();
        String promptVersion = outOfScope ? null : "mock-ask-answer";
        List<String> bundles = outOfScope ? List.of() : List.of("counterparties");

        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("case_no", request.caseNo())
                .put("generation_id", generationId)
                .put("status", rule.status().name())
                .put("kind", rule.kind() == null ? null : rule.kind().name())
                .put("out_of_scope_reason", rule.reason() == null ? null : rule.reason().name())
                .put("answer", answer);
        cites.forEach(raw.putArray("cites")::add);
        evidenceIds.forEach(raw.putArray("evidence_ids")::add);
        com.fasterxml.jackson.databind.node.ArrayNode checks = raw.putArray("code_checks");
        if (answered || rule.status() == AskStatus.UNVERIFIED) {
            checks.addObject().put("check", "COUNT_MATCH").putNull("section")
                    .put("draft_value", answered ? "3개" : "4개").put("source_value", "counterparties.count=3")
                    .put("match", answered).put("reason", answered ? null : "MISMATCH");
        }
        AskGenerationMeta meta = new AskGenerationMeta("mock-0.0.1", promptVersion, "mock-ask-plan", bundles,
                "mock", 0, 0, 0);
        raw.set("generation_meta", om.valueToTree(meta));
        return new AskResponse(producedAt, request.caseNo(), generationId, rule.status(), rule.kind(), rule.reason(),
                answer, cites, evidenceIds, checks, meta, raw);
    }

    /**
     * 참고 사례 검색 목업 고정 목록 (SCRUM-309, 설계 6.3). 1·2번은 계약 예시(`reference_search_response_found.json`) 값,
     * 3번은 AI 코퍼스에 있는 문서. 점수 내림차순. 유형·출처는 요청에서 채운다.
     */
    private record MockReference(String docId, String docTitle, String publisher, Integer year, int page,
                                 double score, String chunkId) {}

    private static final List<MockReference> MOCK_REFERENCES = List.of(
            new MockReference("fintrac-underground-banking-2023",
                    "Updated indicators: Laundering the proceeds of crime through underground banking schemes (FINTRAC-2023-OA002)",
                    "FINTRAC", 2023, 4, 0.52, "34be9b160cd475045c1334f957180e4947e328af50bb33bf14912c12e11dbe9d"),
            new MockReference("ffiec-redflags",
                    "BSA/AML Manual Appendix F: Money Laundering and Terrorist Financing Red Flags",
                    "FFIEC", null, 6, 0.41, "d994051861cbf0626d93ddce92d3af60ec5d69797774c1200b1df9a8adbd2234"),
            new MockReference("fatf-pml-2018", "Professional Money Laundering",
                    "FATF", 2018, 12, 0.33, "0000000000000000000000000000000000000000000000000000000000000003"));

    /** 이 유형이 하나라도 있으면 EMPTY — 채영 님 화면이 FOUND·EMPTY 둘 다 목업으로 보이게. */
    private static final String MOCK_EMPTY_TYPOLOGY = "SCATTER_GATHER";
    private static final String MOCK_RETRIEVAL_VERSION = "mock-retrieval";
    /** 고정 목록은 공개 문서라 출처는 늘 PUBLIC. 지금 AI 가 받는 출처도 이것 하나(`APPROVED_STR` 는 400, SCRUM-310 전). */
    private static final String PUBLIC_SOURCE = "PUBLIC";

    /**
     * 보낸 유형에 `SCATTER_GATHER` 가 있으면 EMPTY, 그 밖은 FOUND 고정 목록을 `top_k` 만큼. 결과 `typologies` 는 보낸 유형
     * 그대로, `source_type` 은 PUBLIC — 실제 응답 확인(`requireReferenceSearchResponse`)을 통과하는 모양.
     * `source_types` 가 `["PUBLIC"]` 이 아니면(`APPROVED_STR`·빈 목록·중복·모르는 값) 실제 AI 처럼 400 → AI_INVALID_REQUEST.
     */
    @Override
    public ReferenceSearchResponse searchReferences(String draftId, ReferenceSearchRequest request) {
        if (!List.of(PUBLIC_SOURCE).equals(request.sourceTypes())) {
            throw new AiClientException("AI_INVALID_REQUEST", "AI 참고 사례 검색 요청이 거부됨", Map.of(), 502);
        }
        List<String> typologies = request.typologies() == null ? List.of() : List.copyOf(request.typologies());
        String sourceType = PUBLIC_SOURCE;
        boolean empty = typologies.contains(MOCK_EMPTY_TYPOLOGY);
        int count = empty ? 0 : Math.max(0, Math.min(MOCK_REFERENCES.size(), request.topK()));
        ReferenceSearchStatus status = count == 0 ? ReferenceSearchStatus.EMPTY : ReferenceSearchStatus.FOUND;

        List<ReferenceResult> results = MOCK_REFERENCES.subList(0, count).stream()
                .map(m -> new ReferenceResult(m.docId(), m.docTitle(), m.publisher(), m.year(), m.page(), m.score(),
                        m.chunkId(), typologies, sourceType))
                .toList();
        ReferenceSearchMeta meta = new ReferenceSearchMeta(false, results.size(), 0);
        String producedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();

        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("status", status.name());
        ArrayNode rawResults = raw.putArray("results");
        for (ReferenceResult r : results) {
            ObjectNode item = rawResults.addObject()
                    .put("doc_id", r.docId()).put("doc_title", r.docTitle()).put("publisher", r.publisher())
                    .put("year", r.year()).put("page", r.page()).put("score", r.score()).put("chunk_id", r.chunkId());
            typologies.forEach(item.putArray("typologies")::add);
            item.put("source_type", r.sourceType());
        }
        raw.put("retrieval_version", MOCK_RETRIEVAL_VERSION);
        raw.putObject("search_meta").put("degraded", meta.degraded())
                .put("candidates", meta.candidates()).put("latency_ms", meta.latencyMs());
        return new ReferenceSearchResponse(producedAt, status, results, MOCK_RETRIEVAL_VERSION, meta, raw);
    }

    /** 이 유형이 하나라도 있으면 SKIPPED·RAW_ACCOUNT — 검색 목업과 같은 고르는 법(채영 님이 두 상태를 목업으로 보게). */
    private static final String MOCK_RAW_ACCOUNT_TYPOLOGY = "SCATTER_GATHER";
    /** AI `STR_INDEX_MIN_CHARS` — 섹션 글에서 공백을 뺀 글자 수(코드 포인트) 합이 이보다 작으면 TOO_SHORT. */
    private static final int MOCK_STR_INDEX_MIN_CHARS = 100;

    /**
     * 승인 STR 색인 목업 (SCRUM-357). AI 와 같은 순서로 고른다: ① 유형에 `SCATTER_GATHER` → SKIPPED·RAW_ACCOUNT
     * ② 공백 뺀 글 합 100 미만 → SKIPPED·TOO_SHORT ③ 그 밖 INDEXED, 청크 수 = 공백만이 아닌 섹션 수(AI 처럼 빈 섹션은 뺌).
     * DISABLED·NO_FACTS 는 없다 — 꺼짐은 부르는 쪽 설정이 먼저 막고, NO_FACTS 는 진짜 AI 에서만. 실제 응답 확인
     * (`requireStrIndexResponse`)을 통과하는 모양.
     */
    @Override
    public StrIndexResponse indexApprovedStr(StrIndexRequest request) {
        List<String> typologies = request.typologies() == null ? List.of() : request.typologies();
        List<StrIndexSection> sections = request.sections() == null ? List.of() : request.sections();
        StrIndexSkipReason reason = null;
        if (typologies.contains(MOCK_RAW_ACCOUNT_TYPOLOGY)) {
            reason = StrIndexSkipReason.RAW_ACCOUNT;
        } else if (sections.stream().mapToLong(s -> nonSpaceChars(s.text())).sum() < MOCK_STR_INDEX_MIN_CHARS) {
            reason = StrIndexSkipReason.TOO_SHORT;
        }
        StrIndexStatus status = reason == null ? StrIndexStatus.INDEXED : StrIndexStatus.SKIPPED;
        int chunks = reason != null ? 0 : (int) sections.stream().filter(s -> nonSpaceChars(s.text()) > 0).count();
        String producedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();

        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("report_no", request.reportNo())
                .put("status", status.name())
                .put("skip_reason", reason == null ? null : reason.name())
                .put("chunk_count", chunks)
                .put("retrieval_version", MOCK_RETRIEVAL_VERSION);
        return new StrIndexResponse(producedAt, request.reportNo(), status, reason, chunks, MOCK_RETRIEVAL_VERSION, raw);
    }

    /** 공백 판정은 AI(파이썬 `isspace`)와 같은 {@link WebClientAiClient#isPythonSpace} — JDK 판정은 NEL(U+0085)을 놓친다. */
    private static long nonSpaceChars(String text) {
        return text == null ? 0 : text.codePoints()
                .filter(cp -> cp > Character.MAX_VALUE || !WebClientAiClient.isPythonSpace((char) cp)).count();
    }

    @Override
    public HealthResponse health() {
        return new HealthResponse("ok", "ok", "ok", "mock", "mock-0.0.1", "mock-f0", "mock-r0");
    }
}
