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
 * 규칙: 섹션(V1 6개 또는 V2 7개 — 한 세트만)을 정해진 순서로, section·text만(evidence_ids 제외), NFC 정규화, 공백 없는 JSON, UTF-8 SHA-256.
 * Java와 Python이 같은 값을 내야 한다 → NarrativeHashTest 5번(v1)·7번(v2) 테스트 벡터로 확인.
 */

public final class NarrativeHash {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter CANONICAL_WRITER = JSON.writer().with(new LowercaseHexEscapes());

    private NarrativeHash() {}

    /** 본문 → 16진수 소문자 64자리 해시. V1(6섹션)·V2(7항목) 중 한 세트가 각각 한 번씩이 아니면 IllegalArgumentException. */
    public static String of(List<NarrativeSection> narrative) {
        NarrativeSections.requireOneSet(narrative, "본문 단락");
        return sha256Hex(toCanonicalJson(narrative));
    }

    /**
     * [{"section":"SUMMARY","text":"…"}, …] — 공백 없는 JSON.
     * 단락은 StrSection 순서(규약 §5.2)로 줄 세우고, text는 NFC로 맞추고, evidence_ids는 넣지 않는다.
     */
    private static String toCanonicalJson(List<NarrativeSection> narrative) {
        ArrayNode array = JSON.createArrayNode();
        for (NarrativeSection n : NarrativeSections.inSectionOrder(narrative)) {
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
