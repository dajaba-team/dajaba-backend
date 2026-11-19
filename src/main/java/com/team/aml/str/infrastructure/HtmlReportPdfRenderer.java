package com.team.aml.str.infrastructure;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.team.aml.str.domain.ReportPdfContent;
import com.team.aml.str.domain.ReportPdfRenderer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 보고서 PDF — HTML(XHTML) 을 openhtmltopdf(PDFBox 3) 로 그린다. 한글은 나눔고딕을 넣는다(subset).
 *
 * <p>왜 HTML → PDF 인가: 별지 제1호는 표가 대부분이라 표·페이지 나눔·머리글(모든 쪽 "모의 보고서")을 CSS 로 적는 쪽이
 * PDFBox 로 좌표를 직접 찍는 것보다 짧고 고치기 쉽다. 라이브러리는 LGPL 2.1 — 수정 없이 jar 로만 쓴다.
 *
 * <p>폰트는 앱이 뜰 때 한 번 읽어 둔다({@link ReportPdfFonts#load}) — 없으면 기동 실패(한글이 빠진 PDF 를 조용히 만들지 않게).
 * 렌더링은 호출마다 새 builder — 여러 요청이 동시에 불러도 된다.
 */
@Component
public class HtmlReportPdfRenderer implements ReportPdfRenderer {

    /** str_reports.pdf_template_version. ReportPdfHtml 모양을 바꾸면 올린다(지난 PDF 는 그 모양 그대로 보관). */
    static final String TEMPLATE_VERSION = "pdf_v1";

    private final ReportPdfFonts fonts;

    public HtmlReportPdfRenderer() {
        this(ReportPdfFonts.load());
    }

    HtmlReportPdfRenderer(ReportPdfFonts fonts) {
        this.fonts = fonts;
    }

    @Override
    public String templateVersion() {
        return TEMPLATE_VERSION;
    }

    @Override
    public byte[] render(ReportPdfContent content) {
        String html = ReportPdfHtml.build(content, TEMPLATE_VERSION);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFont(() -> new ByteArrayInputStream(fonts.regular()), ReportPdfFonts.FAMILY, 400,
                BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(fonts.bold()), ReportPdfFonts.FAMILY, 700,
                BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.withHtmlContent(html, null);   // 외부 리소스(이미지·CSS 파일)를 쓰지 않아 기준 주소가 필요 없다
        builder.toStream(out);
        try {
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("보고서 PDF 를 만들지 못했습니다: " + content.reportNo(), e);
        }
        return out.toByteArray();
    }
}
