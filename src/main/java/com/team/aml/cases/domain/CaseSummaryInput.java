package com.team.aml.cases.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * AI 요약에 실제로 보낸 입력(스냅샷)과 그 해시 (CASES 명세 §7.2.1). {@code case_summaries.input_snapshot}·{@code input_sha256}.
 *
 * <p>스냅샷은 요청 본문 JSON 그대로(규약 v0.4 §6.1 — case_no·alert·evidence_transaction_ids·reasons·prior_alert_count·
 * reporting_institution). 해시는 객체 키를 재귀로 정렬하고 공백 없는 JSON 을 UTF-8 로 SHA-256, 16진수 소문자.
 * 배열 순서는 바꾸지 않는다 — evidence_transaction_ids 는 만드는 쪽이 오름차순으로, rule_facts.paths 처럼 순서에
 * 뜻이 있는 배열은 그대로. version·요청자·시각·헤더·응답은 넣지 않는다(요청 본문에 없음).
 *
 * <p>해시는 백엔드 안에서만 비교한다(AI 와 맞출 테스트 벡터 없음 — STR 본문 해시 {@code NarrativeHash} 와 별개).
 */
public record CaseSummaryInput(ObjectNode snapshot, String sha256) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public CaseSummaryInput {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(sha256, "sha256");
    }

    /** 요청 본문(SNAKE_CASE 로 직렬화한 JSON 객체) → 스냅샷 + 해시. 넘긴 노드는 바꾸지 않는다. */
    public static CaseSummaryInput of(JsonNode requestBody) {
        if (!(requestBody instanceof ObjectNode body)) {
            throw new IllegalArgumentException("요약 요청 본문은 JSON 객체여야 합니다");
        }
        ObjectNode snapshot = body.deepCopy();
        return new CaseSummaryInput(snapshot, sha256Hex(canonical(snapshot)));
    }

    /** 키를 재귀로 정렬한 공백 없는 JSON. */
    static String canonical(JsonNode node) {
        try {
            return JSON.writeValueAsString(sorted(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요약 입력 JSON 을 만들 수 없음", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                out.set(name, sorted(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                out.add(sorted(it.next()));
            }
            return out;
        }
        return node;
    }

    private static String sha256Hex(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없음", e);
        }
    }
}
