package com.team.aml.str.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.team.aml.str.application.GetStrDraft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrDraftDetailResponse(
        String draftId,
        String generationId,
        String caseNo,
        String status,
        Long version,
        String generatorVersion,
        Instant producedAt,
        JsonNode facts,
        List<Narrative> narrative,
        Integer currentRevisionNo,
        JsonNode latestVerification
) {

    public static StrDraftDetailResponse from(
            GetStrDraft.Result result
    ) {

        // ADMIN: 상태와 생성 시각만 반환
        if (result.metaOnly()) {
            return new StrDraftDetailResponse(
                    result.draftId(),
                    null,
                    null,
                    result.status().name(),
                    null,
                    null,
                    result.producedAt(),
                    null,
                    null,
                    null,
                    null
            );
        }

        // INVESTIGATOR 담당자 / APPROVER: 전체 반환
        return new StrDraftDetailResponse(
                result.draftId(),
                result.generationId(),
                result.caseNo(),
                result.status().name(),
                result.version(),
                result.generatorVersion(),
                result.producedAt(),
                maskAccountKeys(result.facts()),
                result.narrative().stream()
                        .map(Narrative::from)
                        .toList(),
                result.currentRevisionNo(),
                result.latestVerification()
        );
    }

    /**
     * facts 안의 account_key를 account_masked로 치환한다.
     * 원본 JsonNode는 수정하지 않는다.
     */
    private static JsonNode maskAccountKeys(JsonNode source) {

        JsonNode copy = source.deepCopy();
        maskRecursively(copy);

        return copy;
    }

    private static void maskRecursively(JsonNode node) {

        if (node instanceof ObjectNode object) {

            List<String> names = new ArrayList<>();

            Iterator<String> iterator = object.fieldNames();
            iterator.forEachRemaining(names::add);

            for (String name : names) {

                JsonNode value = object.get(name);

                if ("account_key".equals(name)) {

                    object.remove(name);

                    if (value != null && value.isTextual()) {
                        object.set(
                                "account_masked",
                                TextNode.valueOf(
                                        maskedAccount(value.asText())
                                )
                        );
                    }

                } else {
                    maskRecursively(value);
                }
            }

        } else if (node instanceof ArrayNode array) {
            array.forEach(
                    StrDraftDetailResponse::maskRecursively
            );
        }
    }

    /**
     * 010_8000EBD30 → 010_****BD30
     */
    private static String maskedAccount(String accountKey) {

        int separator = accountKey.indexOf('_');

        String bank = separator < 0
                ? ""
                : accountKey.substring(0, separator + 1);

        String number = separator < 0
                ? accountKey
                : accountKey.substring(separator + 1);

        String tail = number.length() > 4
                ? number.substring(number.length() - 4)
                : "";

        return bank + "****" + tail;
    }

    public record Narrative(
            String section,
            String text,
            List<String> cites,
            @JsonProperty("evidence_ids")
            List<Long> evidenceIds
    ) {

        static Narrative from(
                com.team.aml.str.domain.NarrativeSection section
        ) {
            return new Narrative(
                    section.section().name(),
                    section.text(),
                    section.cites(),
                    section.evidenceIds()
            );
        }
    }
}
