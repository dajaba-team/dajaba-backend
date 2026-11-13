package com.team.aml.str.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.application.GetStrDraft;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrSection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S2 응답. 키는 META_ONLY 여도 모두 나간다(값만 null) — @JsonInclude 를 쓰지 않는다.
 * suspicion_*·generation_id·generator_version·references 는 계약서 S2 토글 (11/13 추가, generation_id·generator_version 은 계약서에 한 줄 추가 필요).
 * facts 의 *account_key 는 원문 대신 *account_no(가린 값)·*account_ref 로 바꾼다 (계약서 2.4, 미결 19).
 */
public record StrDraftDetailResponse(
        String draftNo,
        String access,
        String caseNo,
        String caseStatus,
        String status,
        Long version,
        Long caseVersion,
        Instant producedAt,
        JsonNode facts,
        List<Narrative> narrative,
        Integer currentRevisionNo,
        JsonNode latestVerification,
        String generationId,
        String generatorVersion,
        List<String> suspicionTypes,
        List<String> suspicionTypesRecommended,
        Integer suspicionLevelBranch,
        Integer suspicionLevelHq,
        List<JsonNode> references
) {

    private static final String ACCOUNT_KEY = "account_key";

    public static StrDraftDetailResponse from(GetStrDraft.Result r) {
        return new StrDraftDetailResponse(
                r.draftNo(),
                r.access().name(),
                r.caseNo(),
                r.caseStatus(),
                r.status().name(),
                r.version(),
                r.caseVersion(),
                r.producedAt(),
                r.facts() == null ? null : maskAccountKeys(r.facts()),
                r.narrative() == null ? null : r.narrative().stream().map(Narrative::from).toList(),
                r.currentRevisionNo(),
                r.latestVerification(),
                r.generationId(),
                r.generatorVersion(),
                r.suspicionTypes(),
                r.suspicionTypesRecommended(),
                r.suspicionLevelBranch(),
                r.suspicionLevelHq(),
                r.references());
    }

    /** 원본은 두고 복사본을 고친다. */
    private static JsonNode maskAccountKeys(JsonNode facts) {
        JsonNode copy = facts.deepCopy();
        mask(copy);
        return copy;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (name.endsWith(ACCOUNT_KEY)) {
                    String prefix = name.substring(0, name.length() - ACCOUNT_KEY.length());
                    String key = value == null || value.isNull() ? null : value.asText();
                    object.remove(name);
                    object.put(prefix + "account_no", StrAccountDisplay.maskedNo(key));
                    object.put(prefix + "account_ref", StrAccountDisplay.ref(key));
                } else {
                    mask(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(StrDraftDetailResponse::mask);
        }
    }

    /**
     * label = 화면 단락 제목. AI 응답에는 없어서 백엔드가 섹션 이름으로 붙인다 (11/6 7항목 전환, #84 리뷰).
     * cites 는 규약 v0.4 단락 인용 — 계약서 S2 에 추가 요청 (#84 리뷰).
     */
    public record Narrative(String section, String label, String text, List<String> cites, List<Long> evidenceIds) {

        static Narrative from(NarrativeSection s) {
            return new Narrative(s.section().name(), label(s.section()), s.text(), s.cites(), s.evidenceIds());
        }

        /** V2 = 별지 제1호 서술부 1~7 (규약 §5.2 12번). V1 = 11/6 전 6섹션 초안용. */
        static String label(StrSection section) {
            return switch (section) {
                case SUBJECT -> "거래자";
                case DATE -> "일자";
                case PLACE -> "장소";
                case INSTRUMENT -> "수단";
                case METHOD -> "방법";
                case GROUNDS -> "사유";
                case OPINION -> "종합의견";
                case SUMMARY -> "요약";
                case WHO -> "누가";
                case WHAT_WHEN_WHERE -> "무엇을·언제·어디서";
                case HOW -> "어떻게";
                case WHY_SUSPICIOUS -> "왜 의심되는지";
                case CONCLUSION -> "결론";
            };
        }
    }
}
