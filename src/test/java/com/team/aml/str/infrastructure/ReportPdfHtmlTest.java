package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.team.aml.str.infrastructure.ReportPdfFixtures.preview;
import static com.team.aml.str.infrastructure.ReportPdfFixtures.snapshot;
import static com.team.aml.str.infrastructure.ReportPdfFixtures.submitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 보고서 HTML (스프링·폰트 없이). 기준: 노션 「STR 보고서 양식 필드 정의」 MVP 칸·자료 없는 칸 처리 규칙, 서식 회의 10/30 결정 2·3.
 */
class ReportPdfHtmlTest {

    // 1
    @Test
    void 정상_별지_제1호_순서대로_표제부부터_VII와_내부_결재_정보까지() {
        String html = ReportPdfHtml.build(submitted(snapshot(2)), "pdf_v1");

        assertThat(html).containsSubsequence("표제부", "I. 보고기관", "II. 거래자", "III. 거래내역", "IV. 관련계좌",
                "V. 송금인·수취인", "VI. 의심되는 거래 유형", "VII. 의심스러운 거래의 개요", "내부 결재 정보");
        assertThat(html).contains("STR-2026-000123", "2026-11-18 10:02:03 (KST)");      // 문서번호·보고일자(서울)
        assertThat(html).contains("2026-11-20 23:59:59 (KST, 공휴일 미반영)");            // 마감(서울)
        assertThat(html).contains("마감 내 제출", "form_v1 · pdf_v1");
    }

    // 2
    @Test
    void 정상_모든_쪽_머리에_모의_보고서_문구_미리보기는_제출_전_표시를_더한다() {
        String fin = ReportPdfHtml.build(submitted(snapshot(1)), "pdf_v1");
        String pre = ReportPdfHtml.build(preview(snapshot(1)), "pdf_v1");

        assertThat(fin).contains("@top-center { content: \"" + ReportPdfHtml.MOCK_NOTICE + "\"")
                .doesNotContain("class=\"watermark\"");
        assertThat(pre).contains(ReportPdfHtml.MOCK_NOTICE + " · " + ReportPdfHtml.PREVIEW_NOTICE)
                .contains("<div class=\"watermark\">제출 전</div>");
        assertThat(pre).contains("<th>2. 보고일자</th><td><span class=\"na\">「제출 전」</span></td>");   // 보고일자 빈칸
    }

    // 3
    @Test
    void 정상_값이_없는_칸은_지어내지_않고_빈칸_이유를_붙인다() {
        String html = ReportPdfHtml.build(submitted(snapshot(1)), "pdf_v1");

        assertThat(html).contains("<th>4. 보고담당자 전화번호</th><td><span class=\"na\">「서비스 설정 필요」</span>");
        assertThat(html).contains("<th>84. 계좌개설일자</th><td><span class=\"na\">「원천 미제공」</span>");
        assertThat(html).contains("<th>6·7. 실명번호 구분·번호</th><td><span class=\"na\">「원천 미제공」</span>");
        assertThat(html).doesNotContain("해당 없음", "null");
        assertThat(html).contains("80062E240, 실명 아님");                                  // 식별자는 병기만, 실명번호 칸에 안 넣음
    }

    // 4
    @Test
    void 정상_거래는_순번대로_원문_시각과_원본_통화_금액으로() {
        String html = ReportPdfHtml.build(submitted(snapshot(3)), "pdf_v1");

        assertThat(html).containsSubsequence("104221", "104222", "104223");
        assertThat(html).contains("2022-09-02 01:01:00");                    // 데이터 시각은 시간대 바꾸지 않음
        assertThat(html).contains("42,750.5", "US Dollar", "전신송금", "입금");
        assertThat(html).contains("V-2 타행", "V-1 자행", "Canada Bank #26");
    }

    // 5
    @Test
    void 정상_서술부는_서식_7항목_제목과_줄바꿈을_살린다() {
        String html = ReportPdfHtml.build(submitted(snapshot(1)), "pdf_v1");

        assertThat(html).containsSubsequence("1) 의심스러운 거래자 관련", "6) 혐의거래로 판단한 사유 관련", "7) 종합의견");
        assertThat(html).contains("서로 다른 계좌 3곳에서 입금됨.<br/>하루 안에 집중됨.");
        assertThat(html).contains("케이스 CASE-2022-000057 · 경보 ALT-20220903-000142 · 초안 DRF-000312");
    }

    // 6
    @Test
    void 보안_본문의_태그와_특수문자는_이스케이프해_XML로_읽힌다() {
        String html = ReportPdfHtml.build(submitted(snapshot(1)), "pdf_v1");

        assertThat(html).contains("의심 거래로 판단함 &lt;script&gt;&amp;&lt;/script&gt;").doesNotContain("<script>");
        assertThatCode(() -> xml(html)).doesNotThrowAnyException();                  // openhtmltopdf 는 XML 로 읽는다
    }

    // 7
    @Test
    void 정상_빈_스냅샷이어도_깨지지_않고_빈칸_이유로_채운다() {
        ObjectNode empty = ReportPdfFixtures.OM.createObjectNode();

        String html = ReportPdfHtml.build(preview(empty), "pdf_v1");

        assertThat(html).contains("근거 거래 없음", "상대 계좌 없음");
        assertThatCode(() -> xml(html)).doesNotThrowAnyException();
    }

    // 8
    @Test
    void 정상_거래자_구분은_entity_type으로_COUNTRY_DIRECT는_추정하지_않는다() {
        assertThat(ReportPdfHtml.subjectKind("SOLE_PROPRIETORSHIP")).startsWith("개인사업자");
        assertThat(ReportPdfHtml.subjectKind("PARTNERSHIP")).isEqualTo("법인·단체");
        assertThat(ReportPdfHtml.subjectKind("COUNTRY")).isEqualTo("확인 불가");
        assertThat(ReportPdfHtml.subjectKind("DIRECT")).isEqualTo("확인 불가");
        assertThat(ReportPdfHtml.subjectKind(null)).contains("원천 미제공");
    }

    // 9
    @Test
    void 경계_금액은_소수_자릿수를_그대로_두고_못_읽으면_원문() {
        assertThat(ReportPdfHtml.amount("1234567.000001")).isEqualTo("1,234,567.000001");
        assertThat(ReportPdfHtml.amount("100")).isEqualTo("100");
        assertThat(ReportPdfHtml.amount("abc")).isEqualTo("abc");
    }

    // 10
    @Test
    void 정상_템플릿_고정_문구는_나눔고딕에_있는_글자만_쓴다() {   // 없는 글자는 PDF 에 # 으로 찍힌다
        ReportPdfFonts fonts = ReportPdfFonts.load();

        for (String html : new String[]{ReportPdfHtml.build(submitted(snapshot(2)), "pdf_v1"),
                ReportPdfHtml.build(preview(ReportPdfFixtures.OM.createObjectNode()), "pdf_v1")}) {
            assertThat(html.codePoints().filter(cp -> cp >= 0x20 && !fonts.covers(cp)).mapToObj(Integer::toHexString))
                    .isEmpty();
        }
    }

    // 11
    @Test
    void 경계_esc는_XML에_못_들어가는_글자를_빼고_짝이_맞는_이모지와_동그라미_숫자는_둔다() throws Exception {
        assertThat(ReportPdfHtml.esc("a\uFFFEb\uFFFFc\uD800d\uDC00e\uD83D\uDE00\u2460")).isEqualTo("abcde\uD83D\uDE00\u2460");
        assertThat(ReportPdfHtml.esc("끝\uD800")).isEqualTo("끝");                  // 끝에 홀로 남은 상위 서로게이트

        ObjectNode snap = snapshot(1);
        ((ObjectNode) snap.path("subject")).put("entity_name", "한빛\uFFFF무역\uD800");
        assertThatCode(() -> xml(ReportPdfHtml.build(submitted(snap), "pdf_v1"))).doesNotThrowAnyException();
    }

    // 12
    @Test
    void 정상_VI_의심_유형은_코드가_아니라_분류_제목과_이름표로_서식_순서대로() {   // 11/21 리뷰 — 코드가 그대로 찍히던 버그
        String html = ReportPdfHtml.build(submitted(snapshot(1)), "pdf_v2");          // 고른 순서: VI-4 빈번한 입출금 → VI-2 불특정다수

        assertThat(html).containsSubsequence("VI-2. 의심스러운 거래자의 태도 및 특징 관련", "· 불특정다수와의 거래",
                "VI-4. 거래유형 관련", "· 빈번한 입출금(입출고)");
        assertThat(html).doesNotContain("DEMO_VI");
    }

    // 13
    @Test
    void 정상_모르는_코드는_숨기지_않고_맨_끝에_확인_필요한_유형으로() {   // 프론트 suspicionTypeLabel 과 같은 글자
        assertThat(ReportPdfHtml.suspicionTypes(List.of("VI-9-99", "DEMO_VI5_01", "VI-9-99")))
                .isEqualTo("<b>VI-5. 기타 특징 및 유형</b><br/>· 기타 (특징·유형을 따로 기술)<br/>확인 필요한 유형 (VI-9-99)");
        assertThat(ReportPdfHtml.suspicionTypes(List.of("<x>"))).isEqualTo("확인 필요한 유형 (&lt;x&gt;)");
    }

    // 14
    @Test
    void 경계_유형_배열에_null이나_숫자가_섞여도_null로_찍지_않고_건너뛴다() {   // 11/21 리뷰 4
        ObjectNode snap = snapshot(1);
        ((ArrayNode) snap.path("suspicion").path("types")).removeAll().addNull().add(7).add("DEMO_VI2_09");

        String html = ReportPdfHtml.build(submitted(snap), "pdf_v2");

        assertThat(html).contains("· 불특정다수와의 거래").doesNotContain("(null)", "(7)");

        ((ArrayNode) snap.path("suspicion").path("types")).removeAll().addNull();
        assertThat(ReportPdfHtml.build(submitted(snap), "pdf_v2"))
                .contains("<th>의심 유형</th><td><span class=\"na\">「원천 미제공」</span>");
    }

    private static Document xml(String html) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }
}
