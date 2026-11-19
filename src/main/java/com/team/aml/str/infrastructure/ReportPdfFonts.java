package com.team.aml.str.infrastructure;

import java.io.IOException;
import java.io.InputStream;

/**
 * 보고서 PDF 한글 폰트 — 나눔고딕 (SIL Open Font License 1.1, src/main/resources/fonts/OFL.txt).
 *
 * <p>왜 클래스패스에 넣나: 서버(도커 eclipse-temurin)·맥·윈도우 어디서 돌아도 같은 글꼴로 같은 모양이 나와야 해서
 * 시스템 폰트를 찾지 않는다. 빠지면 한글이 #·빈칸으로 찍힌 PDF 가 조용히 만들어지므로 <b>앱이 뜰 때 바로 실패</b>시킨다.
 *
 * <p>왜 나눔고딕 TTF 인가: PDF 라이브러리(openhtmltopdf → PDFBox 3)는 TrueType 윤곽(glyf) 폰트만 넣을 수 있다.
 * Noto Sans CJK(.ttc/.otf, CFF 윤곽)는 못 쓴다. 쓰는 글자만 넣어서(subset) 파일이 커지지 않는다.
 */
final class ReportPdfFonts {

    static final String FAMILY = "NanumGothic";
    static final String REGULAR = "fonts/NanumGothic-Regular.ttf";
    static final String BOLD = "fonts/NanumGothic-Bold.ttf";

    private final byte[] regular;
    private final byte[] bold;

    private ReportPdfFonts(byte[] regular, byte[] bold) {
        this.regular = regular;
        this.bold = bold;
    }

    /** 클래스패스에서 두 파일을 읽는다. 없거나 TrueType 이 아니면 IllegalStateException(파일 이름 포함). */
    static ReportPdfFonts load() {
        return new ReportPdfFonts(read(REGULAR), read(BOLD));
    }

    byte[] regular() { return regular; }

    byte[] bold() { return bold; }

    private static byte[] read(String path) {
        ClassLoader loader = ReportPdfFonts.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("보고서 PDF 폰트가 없습니다: classpath:" + path
                        + " — src/main/resources/fonts/README.md 대로 나눔고딕 TTF 를 넣어 주세요");
            }
            byte[] bytes = in.readAllBytes();
            if (!isTrueType(bytes)) {
                throw new IllegalStateException("보고서 PDF 폰트가 TrueType(.ttf)이 아닙니다: classpath:" + path);
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("보고서 PDF 폰트를 읽지 못했습니다: classpath:" + path, e);
        }
    }

    /** TrueType 윤곽 폰트의 첫 4바이트 00 01 00 00 (또는 'true'). CFF 폰트('OTTO')·컬렉션('ttcf')은 거른다. */
    static boolean isTrueType(byte[] b) {
        if (b == null || b.length < 4) return false;
        boolean v1 = b[0] == 0x00 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00;
        boolean mac = b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e';
        return v1 || mac;
    }
}
