package com.team.aml.source.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatternFileParserTest {

    private static final String ROW =
            "2022/09/07 16:27,034,0809B8209,028,08AB78591,6191.05,US Dollar,6191.05,US Dollar,Wire,1";
    private static final String BEGIN = "BEGIN LAUNDERING ATTEMPT - FAN-OUT";
    private static final String END = "END LAUNDERING ATTEMPT";

    private static ParsedPatterns parse(String text) throws IOException {
        return PatternFileParser.parse(new BufferedReader(new StringReader(text)));
    }

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void 블록_하나를_한_사건으로_읽고_값은_원문을_유지한다() throws Exception {
        ParsedPatterns parsed = parse(lines(BEGIN, ROW, ROW, END));

        assertThat(parsed.eventCount()).isEqualTo(1);
        assertThat(parsed.rows()).hasSize(2);
        PatternRow row = parsed.rows().get(0);
        assertThat(row.patternId()).isEqualTo("P000001");
        assertThat(row.patternType()).isEqualTo(PatternType.FAN_OUT);
        assertThat(row.timestampText()).isEqualTo("2022/09/07 16:27");
        assertThat(row.fromBank()).isEqualTo("034");
        assertThat(row.fromAccount()).isEqualTo("0809B8209");
        assertThat(row.toBank()).isEqualTo("028");
        assertThat(row.toAccount()).isEqualTo("08AB78591");
        assertThat(row.amountReceived()).isEqualTo("6191.05");
        assertThat(row.receivingCurrency()).isEqualTo("US Dollar");
        assertThat(row.amountPaid()).isEqualTo("6191.05");
        assertThat(row.paymentCurrency()).isEqualTo("US Dollar");
        assertThat(row.paymentFormat()).isEqualTo("Wire");
        assertThat(row.isLaundering()).isEqualTo("1");
    }

    @Test
    void 콜론_뒤_설명은_버리고_블록마다_ID가_늘어난다() throws Exception {
        ParsedPatterns parsed = parse(lines(
                "BEGIN LAUNDERING ATTEMPT - FAN-OUT:  Max 16-degree Fan-Out", ROW,
                "END LAUNDERING ATTEMPT - FAN-OUT", "",
                "BEGIN LAUNDERING ATTEMPT - CYCLE:  Max 12 hops", ROW,
                "END LAUNDERING ATTEMPT - CYCLE"));

        assertThat(parsed.eventCount()).isEqualTo(2);
        assertThat(parsed.rows()).extracting(PatternRow::patternId).containsExactly("P000001", "P000002");
        assertThat(parsed.rows()).extracting(PatternRow::patternType)
                .containsExactly(PatternType.FAN_OUT, PatternType.CYCLE);
    }

    @Test
    void 마지막_사건_번호_다음부터_ID를_매긴다() throws Exception {
        ParsedPatterns parsed = PatternFileParser.parse(
                new BufferedReader(new StringReader(lines(BEGIN, ROW, END, BEGIN, ROW, END))), 370);

        assertThat(parsed.eventCount()).isEqualTo(2);
        assertThat(parsed.rows()).extracting(PatternRow::patternId).containsExactly("P000371", "P000372");
    }

    @Test
    void CRLF와_마지막_줄바꿈을_허용한다() throws Exception {
        ParsedPatterns parsed = parse(BEGIN + "\r\n" + ROW + "\r\n" + END + "\r\n\r\n");

        assertThat(parsed.eventCount()).isEqualTo(1);
        assertThat(parsed.rows().get(0).isLaundering()).isEqualTo("1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("broken")
    void 구조가_어긋나면_줄_번호와_함께_실패한다(String name, String text, int line) {
        assertThatThrownBy(() -> parse(text))
                .isInstanceOfSatisfying(PatternFileFormatException.class,
                        e -> assertThat(e.getLineNumber()).isEqualTo(line));
    }

    static Stream<Arguments> broken() {
        return Stream.of(
                Arguments.of("모르는 유형", lines("BEGIN LAUNDERING ATTEMPT - UNKNOWN", ROW, END), 1),
                Arguments.of("BEGIN 형식 오류", lines("BEGIN LAUNDERING", ROW, END), 1),
                Arguments.of("END 없이 새 BEGIN", lines(BEGIN, ROW, BEGIN, ROW, END), 3),
                Arguments.of("END 없이 파일 끝", lines(BEGIN, ROW), 2),
                Arguments.of("블록 밖 데이터", lines(ROW), 1),
                Arguments.of("BEGIN 없는 END", lines(END), 1),
                Arguments.of("END 형식 오류", lines(BEGIN, ROW, "END LAUNDERING"), 3),
                Arguments.of("END 유형 불일치", lines(BEGIN, ROW, "END LAUNDERING ATTEMPT - CYCLE"), 3),
                Arguments.of("필드 수 부족", lines(BEGIN, "a,b", END), 2),
                Arguments.of("필드 수 초과", lines(BEGIN, ROW + ",x", END), 2),
                Arguments.of("블록 안 빈 줄", lines(BEGIN, "", ROW, END), 2),
                Arguments.of("거래 없는 블록", lines(BEGIN, END), 2),
                Arguments.of("사건 없는 파일", "", 0));
    }

    @Test
    void 오류_메시지에_계좌와_금액을_넣지_않는다() {
        String secret = "2022/09/07 16:27,034,SECRET123,028,08AB78591,777.77";

        assertThatThrownBy(() -> parse(lines(BEGIN, secret, END)))
                .isInstanceOf(PatternFileFormatException.class)
                .hasMessageNotContaining("SECRET123")
                .hasMessageNotContaining("777.77");
    }

    @Test
    void 행을_문자열로_바꿔도_계좌와_금액이_보이지_않는다() throws Exception {
        String text = parse(lines(BEGIN, ROW, END)).rows().get(0).toString();

        assertThat(text).doesNotContain("0809B8209", "6191.05");
    }
}
