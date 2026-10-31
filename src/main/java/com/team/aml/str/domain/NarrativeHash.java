package com.team.aml.str.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

/**
 * 본문 해시 (10/27 안건 23, 규약 v0.2 §5.3).
 * 규칙: 섹션 6개를 정해진 순서로, section·text만(evidence_ids 제외), NFC 정규화, 공백 없는 JSON, UTF-8 SHA-256.
 * Java와 Python이 같은 값을 내야 한다 → NarrativeHashTest 5번(테스트 벡터)으로 확인.
 */

public final class NarrativeHash {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter CANONICAL_WRITER = JSON.writer().with(new LowercaseHexEscapes());
    private static final int SECTION_COUNT = StrSection.values().length;   // 6

    private NarrativeHash() {}

    /** 본문 → 16진수 소문자 64자리 해시. 단락이 6개 각각 한 번씩이 아니면 IllegalArgumentException. */
    public static String of(List<NarrativeSection> narrative) {
        requireEachSectionOnce(narrative);
        return sha256Hex(toCanonicalJson(narrative));
    }

    /**
     * 6개 단락이 각각 한 번씩인지 (AI docs/shared/01 "narrative 해시" 5번, 10/29).
     * 메시지에는 단락 이름과 개수만 넣는다 — 본문 문장은 로그에 남기지 않는다.
     */
    private static void requireEachSectionOnce(List<NarrativeSection> narrative) {
        Map<StrSection, Integer> counts = new EnumMap<>(StrSection.class);
        for (NarrativeSection n : narrative) {
            counts.merge(n.section(), 1, Integer::sum);
        }
        List<StrSection> missing = Arrays.stream(StrSection.values())
                .filter(s -> !counts.containsKey(s))
                .toList();
        List<StrSection> duplicated = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        if (narrative.size() != SECTION_COUNT || !missing.isEmpty() || !duplicated.isEmpty()) {
            throw new IllegalArgumentException(
                    "본문 단락은 " + SECTION_COUNT + "개가 각각 한 번씩이어야 한다"
                            + " (받은 개수 " + narrative.size() + ", 빠짐 " + missing + ", 중복 " + duplicated + ")");
        }
    }

    /**
     * [{"section":"SUMMARY","text":"…"}, …] — 공백 없는 JSON.
     * 단락은 StrSection 순서(규약 §5.2)로 줄 세우고, text는 NFC로 맞추고, evidence_ids는 넣지 않는다.
     */
    private static String toCanonicalJson(List<NarrativeSection> narrative) {
        ArrayNode array = JSON.createArrayNode();
        for (NarrativeSection n : inSectionOrder(narrative)) {      // ← 바뀐 줄
            array.addObject()
                    .put("section", n.section().name())
                    .put("text", Normalizer.normalize(n.text(), Normalizer.Form.NFC));
        }
        try {
            return CANONICAL_WRITER.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("본문 JSON을 만들 수 없음", e);
        }
    }

    /** 단락 순서(enum 선언 순서) 그대로 줄 세운 새 목록. 받은 목록은 건드리지 않는다. */
    private static List<NarrativeSection> inSectionOrder(List<NarrativeSection> narrative) {
        return narrative.stream()
                .sorted(Comparator.comparing(NarrativeSection::section))
                .toList();
    }

    private static String sha256Hex(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);   // 소문자 16진수
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없음", e);   // 자바에 항상 있어서 실제로는 안 남
        }
    }

    /**
     * 제어문자 \\uXXXX 를 소문자 16진수로 쓴다 — Python json.dumps는 \\u001f, Jackson 기본은 \\u001F.
     * \\n·\\t 같은 짧은 이스케이프와 " \\ 는 Jackson 기본 그대로 (Python과 같음).
     */
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
            if (ch >= 0x20) {
                return null;   // 한글 등 ASCII 밖 글자도 여기로 온다 — 그대로 쓴다 (ensure_ascii=False)
            }
            return new SerializedString(String.format("\\u%04x", ch));
        }
    }

}
