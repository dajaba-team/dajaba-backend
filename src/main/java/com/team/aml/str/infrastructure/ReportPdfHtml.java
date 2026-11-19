package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.str.domain.ReportDeadline;
import com.team.aml.str.domain.ReportPdfContent;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 보고서 PDF 의 HTML(XHTML) — 별지 제1호 순서(표제부 → I~VII) + 서식 밖 "내부 결재 정보" (서식 회의 10/30 결정 2·3).
 * 기준: 노션 「STR 보고서 양식 필드 정의 (별지 제1호 기준)」의 MVP 칸, 「자료 없는 칸 처리 규칙」.
 *
 * <p>규칙
 * <ul>
 *   <li>스냅샷(form_v1, StrReportForm) 값만 찍는다. 값이 없으면 지어내지 않고 빈칸 이유를 붙인다 —
 *       「원천 미제공」(IBM 원본에 없음) / 「서비스 설정 필요」(보고기관·담당자 정보). "해당 없음"·"아니오"로 채우지 않는다</li>
 *   <li>모든 페이지 위에 "모의 보고서 · 합성 데이터, 실제 FIU 제출 아님"(결정 2). 미리보기는 "제출 전" 표시를 더한다</li>
 *   <li>거래 데이터 시각(ts)은 원문 그대로(시간대 없음), 시스템 시각(보고일자·승인 일시·마감)은 서울 시각으로</li>
 *   <li>금액은 원본 통화 그대로, 천 단위 쉼표만. 통화끼리 더하지 않는다. 달러 환산은 스냅샷 값만(US Dollar 일 때)</li>
 *   <li>openhtmltopdf 는 XML 로 읽는다 — 모든 값은 {@link #esc} 를 거치고, 태그는 닫는다. 이름 있는 엔티티(&amp;nbsp;)는 안 쓴다</li>
 *   <li>고정 문구는 나눔고딕에 있는 글자만 쓴다 — 없는 글자(①·✓·이모지 등)는 # 으로 찍힌다(ReportPdfHtmlTest 가 확인).
 *       스냅샷 값의 없는 글자는 렌더러가 그리기 전에 □ 로 바꾼다(ReportPdfGlyphs)</li>
 * </ul>
 * 스프링 없이 만들 수 있는 순수 함수 — 모양은 단위 테스트로 본다.
 */
final class ReportPdfHtml {

    static final String MOCK_NOTICE = "모의 보고서 · 합성 데이터, 실제 FIU 제출 아님";
    static final String PREVIEW_NOTICE = "제출 전 미리보기";
    static final String SOURCE_MISSING = "원천 미제공";
    static final String CONFIG_NEEDED = "서비스 설정 필요";

    private static final DateTimeFormatter SYSTEM_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ReportDeadline.BUSINESS_ZONE);

    private ReportPdfHtml() {}

    static String build(ReportPdfContent c, String templateVersion) {
        JsonNode s = c.formSnapshot();
        StringBuilder h = new StringBuilder(16_384);
        h.append("<!DOCTYPE html>\n<html lang=\"ko\"><head><meta charset=\"UTF-8\"/>");
        h.append("<title>").append(esc("의심스러운 거래보고서 " + c.reportNo())).append("</title>");
        h.append("<style>").append(css(c)).append("</style></head><body>");
        if (c.preview()) {
            h.append("<div class=\"watermark\">제출 전</div>");
        }
        h.append("<h1>의심스러운 거래보고서</h1>");
        h.append("<div class=\"sub\">특정 금융거래정보 보고 및 감독규정 별지 제1호 서식 기준 · ")
                .append(c.preview() ? PREVIEW_NOTICE : "모의 제출본").append("</div>");

        heading(h, c);
        institution(h, s.path("institution"));
        subject(h, s.path("subject"));
        transactions(h, s.path("transactions"));
        account(h, s.path("account"));
        counterparties(h, s.path("counterparties"));
        suspicion(h, s.path("suspicion"));
        narrative(h, s.path("narrative"));
        approval(h, c, s.path("approval"), templateVersion);

        h.append("</body></html>");
        return h.toString();
    }

    // ── 표제부 ─────────────────────────────────────────────

    private static void heading(StringBuilder h, ReportPdfContent c) {
        h.append("<h2>표제부</h2><table>");
        row(h, "1. 문서번호", esc(c.reportNo()));
        row(h, "2. 보고일자", c.preview() ? missing("제출 전") : esc(systemTime(c.reportedAt())) + " (KST)");
        row(h, "정정보고 시 종전문서번호", "정정보고 아님");
        h.append("</table>");
    }

    // ── I. 보고기관 ────────────────────────────────────────

    private static void institution(StringBuilder h, JsonNode i) {
        h.append("<h2>I. 보고기관</h2><table>");
        row(h, "1. 보고기관명", value(i, "name", CONFIG_NEEDED));
        row(h, "2. 보고책임자명", value(i, "responsible_name", CONFIG_NEEDED));
        row(h, "3. 보고담당자명", value(i, "staff_name", CONFIG_NEEDED));
        row(h, "4. 보고담당자 전화번호", value(i, "staff_phone", CONFIG_NEEDED));
        h.append("</table>");
    }

    // ── II. 거래자 ─────────────────────────────────────────

    private static void subject(StringBuilder h, JsonNode s) {
        h.append("<h2>II. 거래자</h2><table>");
        String name = text(s, "entity_name");
        String id = text(s, "entity_id");
        String nameCell = name == null ? missing(SOURCE_MISSING)
                : esc(name) + (id == null ? "" : " <span class=\"note\">(IBM 합성 명칭 · 식별자 " + esc(id) + ", 실명 아님)</span>");
        row(h, "5. 거래자(사업자)명", nameCell);
        row(h, "거래자 구분", subjectKind(text(s, "entity_type")));
        row(h, "6·7. 실명번호 구분·번호", missing(SOURCE_MISSING));
        row(h, "8. 국적", value(s, "country_code", SOURCE_MISSING));
        row(h, "9~15. 주소·생년월일·성별·전화·여권", missing(SOURCE_MISSING));
        row(h, "16·25. 직업·업종", value(s, "industry", SOURCE_MISSING));
        h.append("</table>");
    }

    /** entity_type → 서식 구분 (필드 정의 규칙 5, 결정 5-6 — COUNTRY·DIRECT 는 추정하지 않음). */
    static String subjectKind(String entityType) {
        if (entityType == null) return missing(SOURCE_MISSING);
        return switch (entityType) {
            case "INDIVIDUAL" -> "개인";
            case "SOLE_PROPRIETORSHIP" -> "개인사업자 (개인 항목)";
            case "CORPORATION", "PARTNERSHIP" -> "법인·단체";
            case "COUNTRY", "DIRECT" -> "확인 불가";
            default -> esc(entityType);
        };
    }

    // ── III. 거래내역 ──────────────────────────────────────

    private static void transactions(StringBuilder h, JsonNode rows) {
        h.append("<h2>III. 거래내역 (근거 거래)</h2>");
        if (!rows.isArray() || rows.isEmpty()) {
            h.append("<p class=\"note\">근거 거래 없음</p>");
        } else {
            h.append("<table class=\"grid\"><thead><tr>")
                    .append("<th>순번</th><th>32. 거래발생일시</th><th>36. 거래수단</th><th>37. 거래종류</th>")
                    .append("<th>39. 통화</th><th>41. 외국환거래금액</th><th>42. 달러화 환산</th>")
                    .append("<th>상대 계좌</th><th>거래번호</th></tr></thead><tbody>");
            for (JsonNode t : rows) {
                h.append("<tr>");
                cell(h, "c", esc(text(t, "seq")));
                cell(h, "c", value(t, "ts", SOURCE_MISSING, ReportPdfHtml::dataTime));
                cell(h, "c", value(t, "instrument", SOURCE_MISSING));
                cell(h, "c", t.hasNonNull("kind") ? esc(text(t, "kind")) : "확인 불가");
                cell(h, "c", value(t, "currency", SOURCE_MISSING));
                cell(h, "num", value(t, "amount", SOURCE_MISSING, ReportPdfHtml::amount));
                cell(h, "num", value(t, "usd_amount", SOURCE_MISSING, ReportPdfHtml::amount));
                cell(h, "mono", value(t, "counterparty_account_key", SOURCE_MISSING));
                cell(h, "c", esc(text(t, "transaction_id")));
                h.append("</tr>");
            }
            h.append("</tbody></table>");
        }
        h.append("<p class=\"note\">40. 원화거래금액 ").append(missing(SOURCE_MISSING))
                .append(" · 33~35. 거래채널·영업점, 38. 거래상품 ").append(missing(SOURCE_MISSING))
                .append(" · 43·44. 관련계좌·송금계좌 존재 여부 Y · 45. 거래대리인 파악안됨")
                .append(" · 42. 달러화 환산은 미국 달러 거래만(환율표 없음)</p>");
    }

    // ── IV. 관련계좌 ───────────────────────────────────────

    private static void account(StringBuilder h, JsonNode a) {
        h.append("<h2>IV. 관련계좌</h2><table>");
        row(h, "83. 계좌번호", value(a, "account_key", SOURCE_MISSING));
        row(h, "84. 계좌개설일자", value(a, "opened_at", SOURCE_MISSING));
        row(h, "85. 계좌개설 금융회사등 명칭", value(a, "institution", SOURCE_MISSING));
        row(h, "86·87. 개설 영업점", missing(SOURCE_MISSING));
        row(h, "88. 계좌 개설 목적", value(a, "account_purpose", SOURCE_MISSING));
        row(h, "90. 계좌주 성명", value(a, "holder_name", SOURCE_MISSING));
        row(h, "94. 계좌주 국적", value(a, "holder_country_code", SOURCE_MISSING));
        h.append("</table>");
    }

    // ── V. 송금·수취인 ─────────────────────────────────────

    private static void counterparties(StringBuilder h, JsonNode rows) {
        h.append("<h2>V. 송금인·수취인</h2>");
        if (!rows.isArray() || rows.isEmpty()) {
            h.append("<p class=\"note\">상대 계좌 없음</p>");
            return;
        }
        h.append("<table class=\"grid\"><thead><tr><th>구분</th><th>109·129. 계좌번호</th>")
                .append("<th>133. 상대 금융회사등 명칭</th><th>110·131. 계좌주명</th></tr></thead><tbody>");
        for (JsonNode c : rows) {
            JsonNode same = c.get("same_bank");
            String kind = same == null || same.isNull() ? "확인 불가" : same.asBoolean() ? "V-1 자행" : "V-2 타행";
            h.append("<tr>");
            cell(h, "c", kind);
            cell(h, "mono", value(c, "account_key", SOURCE_MISSING));
            cell(h, "", value(c, "bank_name", SOURCE_MISSING));
            cell(h, "", missing(SOURCE_MISSING));
            h.append("</tr>");
        }
        h.append("</tbody></table>");
    }

    // ── VI. 의심 유형·정도 ─────────────────────────────────

    private static void suspicion(StringBuilder h, JsonNode v) {
        List<String> types = new ArrayList<>();
        v.path("types").forEach(t -> types.add(t.asText()));
        h.append("<h2>VI. 의심되는 거래 유형 및 의심 정도</h2><table>");
        row(h, "의심 유형", types.isEmpty() ? missing(SOURCE_MISSING) : esc(String.join(", ", types)));
        row(h, "의심 정도 (지점용)", level(v.path("level_branch")));
        row(h, "의심 정도 (본점용)", level(v.path("level_hq")));
        h.append("</table>");
    }

    private static String level(JsonNode n) {
        return n.canConvertToInt() ? n.asInt() + " / 5" : missing(SOURCE_MISSING);
    }

    // ── VII. 서술부 ────────────────────────────────────────

    private static void narrative(StringBuilder h, JsonNode n) {
        h.append("<h2>VII. 의심스러운 거래의 개요 및 보고 이유</h2><div class=\"narr\">");
        JsonNode sections = n.path("sections");
        if (!sections.isArray() || sections.isEmpty()) {
            h.append("<p>").append(missing(SOURCE_MISSING)).append("</p>");
        }
        for (JsonNode sec : sections) {
            h.append("<h3>").append(esc(sectionLabel(text(sec, "section")))).append("</h3>");
            h.append("<p>").append(paragraph(text(sec, "text"))).append("</p>");
        }
        h.append("</div>");
        JsonNode docs = n.path("related_docs");
        h.append("<table>");
        row(h, "관련문서번호", esc(join(" · ", "케이스 " + text(docs, "case_no"), "경보 " + text(docs, "alert_no"),
                "초안 " + text(docs, "draft_no"))));
        row(h, "첨부파일명", "없음");
        h.append("</table>");
    }

    /** 단락 이름 → 서식 VII 항목 제목. 7항목(narrative_v2~) + 11/6 전 6섹션 승인본. 모르는 이름은 그대로. */
    static String sectionLabel(String section) {
        if (section == null) return "";
        return switch (section) {
            case "SUBJECT" -> "1) 의심스러운 거래자 관련";
            case "DATE" -> "2) 거래 발생일자 관련";
            case "PLACE" -> "3) 거래 발생 장소(영업점) 관련";
            case "INSTRUMENT" -> "4) 금융거래등 수단(상품) 관련";
            case "METHOD" -> "5) 금융거래등 방법(특이거래) 관련";
            case "GROUNDS" -> "6) 혐의거래로 판단한 사유 관련";
            case "OPINION" -> "7) 종합의견";
            case "SUMMARY" -> "요약";
            case "WHO" -> "누가";
            case "WHAT_WHEN_WHERE" -> "무엇을·언제·어디서";
            case "HOW" -> "어떻게";
            case "WHY_SUSPICIOUS" -> "왜 의심되는지";
            case "CONCLUSION" -> "결론";
            default -> section;
        };
    }

    // ── (서식 외) 내부 결재 정보 ───────────────────────────

    private static void approval(StringBuilder h, ReportPdfContent c, JsonNode a, String templateVersion) {
        h.append("<h2>(서식 외) 내부 결재 정보</h2><table>");
        row(h, "작성 조사역", value(a, "investigator_name", CONFIG_NEEDED));
        row(h, "승인자", value(a, "approver_name", CONFIG_NEEDED));
        row(h, "승인 일시", value(a, "approved_at", SOURCE_MISSING, ReportPdfHtml::systemTimeText));
        row(h, "보고 마감", esc(systemTime(c.reportDueAt())) + " (KST, 공휴일 미반영)");
        row(h, "마감 준수", c.preview() ? missing("제출 전") : Boolean.TRUE.equals(c.late()) ? "마감 뒤 제출" : "마감 내 제출");
        row(h, "본문 해시 (SHA-256)", value(a, "narrative_sha256", SOURCE_MISSING));
        row(h, "생성 번호 · 버전", esc(join(" · ", text(a, "generation_id"), text(a, "generator_version"))));
        row(h, "검증 번호 · 버전", esc(join(" · ", text(a, "verification_id"), text(a, "verifier_version"))));
        row(h, "서식 · 템플릿 버전", esc(c.snapshotVersion() + " · " + templateVersion));
        h.append("</table>");
    }

    // ── 모양 ──────────────────────────────────────────────

    private static String css(ReportPdfContent c) {
        String top = c.preview() ? MOCK_NOTICE + " · " + PREVIEW_NOTICE : MOCK_NOTICE;
        String f = "font-family: " + ReportPdfFonts.FAMILY + ";";
        return "@page { size: A4; margin: 20mm 14mm 18mm 14mm;"
                + " @top-center { content: \"" + cssString(top) + "\"; " + f + " font-size: 8pt; color: #b00020; }"
                + " @bottom-center { content: \"" + cssString("문서번호 " + c.reportNo()) + "  \" counter(page) \" / \" counter(pages); "
                + f + " font-size: 8pt; color: #555555; } }"
                + " body { " + f + " font-size: 9pt; line-height: 1.45; color: #111111; }"
                + " h1 { font-size: 16pt; font-weight: bold; text-align: center; margin: 0 0 1mm 0; }"
                + " .sub { text-align: center; font-size: 8pt; color: #555555; margin-bottom: 4mm; }"
                + " h2 { font-size: 10.5pt; font-weight: bold; margin: 4mm 0 1.5mm 0; padding: 1mm 2mm;"
                + " background-color: #eef1f5; border-left: 3px solid #2f4b7c; page-break-after: avoid; }"
                + " h3 { font-size: 9.5pt; font-weight: bold; margin: 2.5mm 0 1mm 0; page-break-after: avoid; }"
                + " table { width: 100%; border-collapse: collapse; margin-bottom: 1.5mm; }"
                + " th, td { border: 0.6pt solid #9a9a9a; padding: 1.1mm 1.5mm; vertical-align: top; word-wrap: break-word; }"
                + " th { background-color: #f4f4f4; font-weight: bold; text-align: left; width: 32%; }"
                + " table.grid th { width: auto; text-align: center; font-size: 7.5pt; }"
                + " table.grid td { font-size: 7.5pt; }"
                + " thead { display: table-header-group; }"
                + " tr { page-break-inside: avoid; }"
                + " td.num { text-align: right; } td.c { text-align: center; }"
                + " .na { color: #8a8a8a; } .note { color: #555555; font-size: 7.5pt; }"
                + " .narr p { margin: 0 0 1mm 0; }"
                + " .watermark { position: fixed; top: 105mm; left: 0; width: 100%; text-align: center;"
                + " font-size: 72pt; font-weight: bold; color: #ececec; transform: rotate(-30deg); }";
    }

    // ── 도우미 ────────────────────────────────────────────

    private static void row(StringBuilder h, String label, String cellHtml) {
        h.append("<tr><th>").append(esc(label)).append("</th><td>").append(cellHtml).append("</td></tr>");
    }

    private static void cell(StringBuilder h, String cssClass, String cellHtml) {
        h.append(cssClass.isEmpty() ? "<td>" : "<td class=\"" + cssClass + "\">").append(cellHtml).append("</td>");
    }

    /** 값이 있으면 이스케이프한 값, 없으면 빈칸 이유. */
    private static String value(JsonNode node, String field, String missingReason) {
        return value(node, field, missingReason, s -> s);
    }

    private static String value(JsonNode node, String field, String missingReason,
                                java.util.function.UnaryOperator<String> format) {
        String v = text(node, field);
        return v == null || v.isBlank() ? missing(missingReason) : esc(format.apply(v));
    }

    static String missing(String reason) {
        return "<span class=\"na\">「" + esc(reason) + "」</span>";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    /** null 은 빼고 잇는다. "케이스 null" 처럼 null 을 붙인 조각도 뺀다. 다 비면 빈 문자열. */
    private static String join(String sep, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.endsWith(" null") && !p.isBlank()) kept.add(p);
        }
        return String.join(sep, kept);
    }

    /** 본문: 이스케이프 후 줄바꿈만 &lt;br/&gt; 로. */
    private static String paragraph(String text) {
        if (text == null || text.isBlank()) return missing(SOURCE_MISSING);
        return esc(text).replace("\r\n", "\n").replace("\n", "<br/>");
    }

    /** 거래 데이터 시각 — 원문 그대로, T 만 띄어쓰기로 (시간대 바꾸지 않음, 계약서 2.4). */
    static String dataTime(String ts) {
        return ts.replace('T', ' ');
    }

    static String systemTime(Instant t) {
        return SYSTEM_TIME.format(t);
    }

    /** 스냅샷의 UTC Z 문자열 → 서울 시각. 못 읽으면 원문. */
    private static String systemTimeText(String iso) {
        try {
            return systemTime(Instant.parse(iso)) + " (KST)";
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** "42750.5" → "42,750.5". 소수 자릿수는 원문 그대로(최대 6자리, numeric(20,6)). 못 읽으면 원문. */
    static String amount(String plain) {
        try {
            DecimalFormat f = new DecimalFormat("#,##0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));
            return f.format(new BigDecimal(plain));
        } catch (RuntimeException e) {
            return plain;
        }
    }

    /**
     * XML 텍스트·속성 이스케이프. XML 에 못 들어가는 글자는 뺀다 — 제어 문자(탭·줄바꿈 제외), U+FFFE·U+FFFF,
     * 짝 없는 서로게이트. 남겨 두면 openhtmltopdf 의 XML 읽기가 실패하고, 스냅샷은 안 바뀌어서 다시 해도 계속 실패한다.
     */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> {
                    if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(ch).append(s.charAt(++i));                     // 짝이 맞는 것만 (이모지 등)
                    } else if (Character.isSurrogate(ch) || ch == '\uFFFE' || ch == '\uFFFF') {
                        // 뺀다
                    } else if (ch >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
                        b.append(ch);
                    }
                }
            }
        }
        return b.toString();
    }

    /** CSS 문자열 안 — 따옴표·역슬래시만 막는다(값은 문서번호·고정 문구). */
    private static String cssString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
