package com.team.aml.str.infrastructure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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
                    "거래자 한빛무역은 법인임.", "7) 종합의견", "원천 미제공");
            assertThat(text).doesNotContain("#####");
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
    void 정상_템플릿_버전은_pdf_v1이고_str_reports_칸_길이_안() {
        assertThat(RENDERER.templateVersion()).isEqualTo("pdf_v1").hasSizeLessThanOrEqualTo(32);
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
}
