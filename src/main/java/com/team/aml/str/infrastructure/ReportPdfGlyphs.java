package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * 스냅샷 글자 중 폰트에 없는 것을 □ 로 바꾼다 — 그대로 두면 PDF 에 # 으로 조용히 찍힌다(11/19 폰트 결정과 같은 이유).
 *
 * <p>템플릿 고정 문구는 폰트에 있는 글자만 쓴다(ReportPdfHtmlTest 가 확인). 위험한 건 조사역이 S3 에서 고친 본문·LLM 문장
 * 같은 스냅샷 값이라 문자열 값만 본다. 제어 문자(0x20 미만)는 {@link ReportPdfHtml#esc} 가 처리하므로 건너뛴다.
 * 짝 없는 서로게이트·U+FFFE·U+FFFF 도 폰트에 없어서 여기서 □ 가 된다.
 */
final class ReportPdfGlyphs {

    private ReportPdfGlyphs() {}

    /** @param snapshot 바꾼 사본 (원본은 그대로) @param replaced 바꾼 코드포인트 (작은 순, 중복 없음) */
    record Result(JsonNode snapshot, SortedSet<Integer> replaced) {}

    static Result replaceUncovered(JsonNode snapshot, IntPredicate covered) {
        JsonNode copy = snapshot.deepCopy();
        SortedSet<Integer> replaced = new TreeSet<>();
        walk(copy, covered, replaced);
        return new Result(copy, replaced);
    }

    private static void walk(JsonNode node, IntPredicate covered, SortedSet<Integer> replaced) {
        if (node instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual()) {
                    e.setValue(TextNode.valueOf(replace(e.getValue().textValue(), covered, replaced)));
                } else {
                    walk(e.getValue(), covered, replaced);
                }
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i).isTextual()) {
                    a.set(i, TextNode.valueOf(replace(a.get(i).textValue(), covered, replaced)));
                } else {
                    walk(a.get(i), covered, replaced);
                }
            }
        }
    }

    static String replace(String s, IntPredicate covered, SortedSet<Integer> replaced) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            boolean keep = cp < 0x20 || covered.test(cp);
            if (!keep) {
                if (b == null) b = new StringBuilder(s.length()).append(s, 0, i);
                b.appendCodePoint(ReportPdfFonts.REPLACEMENT);
                replaced.add(cp);
            } else if (b != null) {
                b.appendCodePoint(cp);
            }
            i += len;
        }
        return b == null ? s : b.toString();
    }

    /** 로그용 "U+2460, U+1F600". */
    static String describe(SortedSet<Integer> codePoints) {
        StringBuilder b = new StringBuilder();
        for (int cp : codePoints) {
            if (!b.isEmpty()) b.append(", ");
            b.append(String.format("U+%04X", cp));
        }
        return b.toString();
    }
}
