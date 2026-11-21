package com.team.aml.str.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static com.team.aml.str.infrastructure.ReportPdfFixtures.preview;
import static com.team.aml.str.infrastructure.ReportPdfFixtures.snapshot;
import static com.team.aml.str.infrastructure.ReportPdfFixtures.submitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 실제 PDF (스프링 없이, 클래스패스의 나눔고딕). 만든 PDF 를 PDFBox 로 다시 열어 글자·글꼴·쪽 머리를 본다.
 * 한글이 # 이나 빈칸으로 찍히면 글자 뽑기에서 한글이 안 나온다.
 */
class HtmlReportPdfRendererTest {

    private static final HtmlReportPdfRenderer RENDERER = new HtmlReportPdfRenderer();

    // 1
    @Test
    void 정상_한글이_그대로_읽히는_PDF가_나온다() throws IOException {
        byte[] pdf = RENDERER.render(submitted(snapshot(2)));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("의심스러운 거래보고서", "STR-2026-000123", "한빛무역", "전신송금",
                    "거래자 한빛무역은 법인임.", "7) 종합의견", "원천 미제공", "불특정다수와의 거래");
            assertThat(text).doesNotContain("#####", "DEMO_VI");                       // VI 유형은 코드가 아니라 이름표
        }
    }

    // 2
    @Test
    void 정상_나눔고딕이_PDF_안에_들어간다() throws IOException {   // 보는 기계에 글꼴이 없어도 같은 모양
        byte[] pdf = RENDERER.render(submitted(snapshot(1)));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<String> fonts = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                for (COSName name : res.getFontNames()) {
                    fonts.add(res.getFont(name).getName());
                }
            }
            assertThat(fonts).anySatisfy(f -> assertThat(f).contains("NanumGothic"));
            assertThat(fonts).allSatisfy(f -> assertThat(f).contains("NanumGothic"));   // 대체 글꼴(Helvetica 등)로 안 빠짐
        }
    }

    // 3
    @Test
    void 정상_여러_쪽이어도_모든_쪽_머리에_모의_보고서_문구와_쪽번호() throws IOException {
        byte[] pdf = RENDERER.render(submitted(snapshot(80)));                 // 거래 80건 — 여러 쪽

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                assertThat(text).as("%d쪽", p).contains(ReportPdfHtml.MOCK_NOTICE)
                        .contains(p + " / " + pages);
            }
        }
    }

    // 4
    @Test
    void 정상_미리보기는_모든_쪽에_제출_전_표시() throws IOException {
        byte[] pdf = RENDERER.render(preview(snapshot(80)));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                assertThat(stripper.getText(doc)).as("%d쪽", p).contains(ReportPdfHtml.PREVIEW_NOTICE);
            }
        }
    }

    // 5
    @Test
    void 정상_템플릿_버전은_pdf_v2이고_str_reports_칸_길이_안() {   // v2: VI 이름표 (11/21)
        assertThat(RENDERER.templateVersion()).isEqualTo("pdf_v2").hasSizeLessThanOrEqualTo(32);
    }

    // 6
    @Test
    void 예외_폰트가_TrueType이_아니면_뜰_때_바로_실패() {
        assertThat(ReportPdfFonts.isTrueType(new byte[]{0, 1, 0, 0, 9})).isTrue();
        assertThat(ReportPdfFonts.isTrueType("OTTO".getBytes(StandardCharsets.US_ASCII))).isFalse();   // CFF(.otf)
        assertThat(ReportPdfFonts.isTrueType("ttcf".getBytes(StandardCharsets.US_ASCII))).isFalse();   // 컬렉션(.ttc)
        assertThat(ReportPdfFonts.isTrueType(new byte[]{1})).isFalse();
        assertThatCode(() -> ReportPdfFonts.load()).doesNotThrowAnyException();         // 리포에 들어 있다
    }

    // 7
    @Test
    void 정상_cmap으로_폰트에_있는_글자와_없는_글자를_가린다() {   // 11/19 cmap 직접 확인 결과와 같아야 한다
        ReportPdfFonts fonts = ReportPdfFonts.load();

        assertThat(fonts.covers('한')).isTrue();
        assertThat(fonts.covers('→')).isTrue();
        assertThat(fonts.covers('—')).isTrue();
        assertThat(fonts.covers('·')).isTrue();
        assertThat(fonts.covers('「')).isTrue();
        assertThat(fonts.covers(ReportPdfFonts.REPLACEMENT)).isTrue();
        assertThat(fonts.covers(0x2460)).isFalse();                         // ①
        assertThat(fonts.covers(0x2713)).isFalse();                         // ✓
        assertThat(fonts.covers(0x1F600)).isFalse();                        // 이모지
        assertThat(fonts.covers(0xFFFD)).isFalse();
        assertThat(fonts.covers(0xFFFF)).isFalse();
        assertThat(fonts.covers(-1)).isFalse();
        assertThat(fonts.covers(0x110000)).isFalse();
    }

    // 8
    @Test
    void 정상_스냅샷의_없는_글자는_네모로_바꾸고_원본은_건드리지_않는다() {
        ObjectNode snap = snapshot(1);
        ((ObjectNode) snap.path("narrative").path("sections").get(0)).put("text", "\u2460 송금 → 환전 — 반복 \uD83D\uDE00\n끝");
        ((ArrayNode) snap.path("suspicion").path("types")).add("유형\u2713");
        ReportPdfFonts fonts = ReportPdfFonts.load();

        ReportPdfGlyphs.Result r = ReportPdfGlyphs.replaceUncovered(snap, fonts::covers);

        assertThat(r.snapshot().path("narrative").path("sections").get(0).path("text").asText())
                .isEqualTo("□ 송금 → 환전 — 반복 □\n끝");                   // 있는 글자·줄바꿈은 그대로
        assertThat(r.snapshot().path("suspicion").path("types").get(2).asText()).isEqualTo("유형□");   // 배열 안 문자열도
        assertThat(r.snapshot().path("transactions").get(0).path("seq").asInt()).isEqualTo(1);      // 숫자는 그대로
        assertThat(r.replaced()).containsExactly(0x2460, 0x2713, 0x1F600);
        assertThat(ReportPdfGlyphs.describe(r.replaced())).isEqualTo("U+2460, U+2713, U+1F600");
        assertThat(snap.path("narrative").path("sections").get(0).path("text").asText()).startsWith("\u2460");
    }

    // 9
    @Test
    void 정상_없는_글자가_있어도_PDF는_샵_없이_네모로_찍히고_보고서_번호와_코드포인트를_WARN으로_남긴다() throws IOException {
        ObjectNode snap = snapshot(1);
        ((ObjectNode) snap.path("narrative").path("sections").get(0)).put("text", "\u2460 반복 \uD83D\uDE00 → 환전");
        ((ObjectNode) snap.path("subject")).put("entity_name", "한빛\uFFFF무역");   // jsonb 가 받아 주는 XML 금지 글자
        Logger logger = (Logger) LoggerFactory.getLogger(HtmlReportPdfRenderer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            byte[] pdf = RENDERER.render(submitted(snap));

            try (PDDocument doc = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(doc);
                assertThat(text).contains("□ 반복 □ → 환전", "한빛□무역")
                        .doesNotContain("# 반복", "반복 #", "한빛#");   // 픽스처 은행명 "Bank #4507" 의 # 은 원래 값
            }
            assertThat(logs.list).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains(ReportPdfFixtures.REPORT_NO, "U+2460", "U+FFFF", "U+1F600");
            });
        } finally {
            logger.detachAppender(logs);
        }
    }

    // 10
    @Test
    void 정상_없는_글자가_없으면_로그도_내용도_그대로() {
        var content = submitted(snapshot(1));

        assertThat(RENDERER.coveredOnly(content)).isSameAs(content);
        SortedSet<Integer> none = new TreeSet<>();
        assertThat(ReportPdfGlyphs.replace("한빛무역 → —", cp -> true, none)).isEqualTo("한빛무역 → —");
        assertThat(none).isEmpty();
    }
}
