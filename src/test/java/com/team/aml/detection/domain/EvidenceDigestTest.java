package com.team.aml.detection.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 경보 근거 digest (SCRUM-415, 규약 6절 근거 신원 칸 — AI `docs/scoring/01` 「근거 신원 칸」이 정본).
 * 근거 거래마다 `원본파일명:원본행번호` → (파일명 문자열, 행번호 정수) 오름차순 → `\n` 으로 이음(끝 줄바꿈 없음) → UTF-8 SHA-256 소문자 16진.
 * 기대값은 이 테스트 안에서 따로 계산하거나 9/7 점검 예시(AI 와 같은 값)로 고정한다.
 */
class EvidenceDigestTest {

    private static final String FILE = "HI-Small_Trans.csv";

    // 9/7 점검 예시 — 새 스냅샷과 시연 DB 가 1,545건 모두 같은 값을 냄. 행 순서는 원본 목록 그대로.
    private static final long[] ROWS_000001 = {1986552L, 2046999L, 2047000L, 2084563L, 2098137L, 2270959L, 2270960L,
            2294529L, 2324273L, 2324274L, 2333352L, 2333353L, 2510223L, 2606610L, 2674739L, 2674740L, 2693683L,
            2693684L, 2776966L, 2796240L, 2961883L, 2961884L, 3010278L, 3010279L, 3040407L, 3040408L, 3059393L,
            3187179L, 3187180L, 3259121L};
    /** 행번호 정수 정렬과 문자열 정렬이 갈리는 경보(`318130` < `1639618`) — 9/7 에 이런 경보 15건. */
    private static final long[] ROWS_001157 = {318130L, 1639618L, 1639619L, 1892453L, 1892455L, 1984627L, 1984628L,
            1984629L, 2012558L, 2012559L, 2073417L, 2073418L, 2073420L, 2196201L, 2196203L, 2821099L, 3123487L,
            3469218L};
    private static final long[] ROWS_001528 = {2332835L, 2332837L, 2332838L, 2332840L, 2332842L, 2332843L};

    @Test
    void 정상_9_7_예시_3건이_AI_와_같은_값() {
        assertThat(EvidenceDigest.of(sources(ROWS_000001)))
                .isEqualTo("d277a2cdcb9bd308cd75c3cf55b4cb15f0a99e5cb894728bffe1c610960caade");
        assertThat(EvidenceDigest.of(sources(ROWS_001157)))
                .isEqualTo("099b951a700949628505e7f269b064b514727218dc37004b73df2196da6516e8");
        assertThat(EvidenceDigest.of(sources(ROWS_001528)))
                .isEqualTo("c9132814329f2e30d0dc16483ede794085f299f698f9b4102fbe0d525b3c33f1");
    }

    @Test
    void 경계_001157_은_행번호를_문자열로_정렬하면_다른_값이라_정수_정렬이어야_맞는다() {
        List<String> textSorted = new ArrayList<>();
        for (long r : ROWS_001157) {
            textSorted.add(FILE + ":" + r);
        }
        Collections.sort(textSorted);
        String wrong = sha256(String.join("\n", textSorted));

        assertThat(wrong).isNotEqualTo("099b951a700949628505e7f269b064b514727218dc37004b73df2196da6516e8");
        assertThat(EvidenceDigest.of(sources(ROWS_001157))).isNotEqualTo(wrong);
    }

    @Test
    void 정상_입력_순서를_섞어도_같은_값() {
        List<EvidenceSource> shuffled = new ArrayList<>(sources(ROWS_001157));
        Collections.shuffle(shuffled, new Random(415));
        Collections.reverse(shuffled.subList(0, 9));

        assertThat(EvidenceDigest.of(List.copyOf(shuffled))).isEqualTo(EvidenceDigest.of(sources(ROWS_001157)));
    }

    @Test
    void 정상_파일명이_먼저_문자열_순_그다음_행번호_정수_순() {
        List<EvidenceSource> in = List.of(
                new EvidenceSource("b.csv", 2L),
                new EvidenceSource("a.csv", 10L),
                new EvidenceSource("b.csv", 1L),
                new EvidenceSource("a.csv", 9L),
                new EvidenceSource("B.csv", 5L));   // 대문자 'B'(0x42) < 'a'(0x61)

        assertThat(EvidenceDigest.of(in)).isEqualTo(sha256("B.csv:5\na.csv:9\na.csv:10\nb.csv:1\nb.csv:2"));
    }

    @Test
    void 경계_한_줄이면_끝_줄바꿈_없이_그_줄만() {
        assertThat(EvidenceDigest.of(List.of(new EvidenceSource(FILE, 7L)))).isEqualTo(sha256(FILE + ":7"));
        assertThat(EvidenceDigest.of(List.of(new EvidenceSource(FILE, 7L)))).isNotEqualTo(sha256(FILE + ":7\n"));
    }

    @Test
    void 경계_UTF_8_파일명() {
        assertThat(EvidenceDigest.of(List.of(new EvidenceSource("거래_원본.csv", 1L))))
                .isEqualTo(sha256("거래_원본.csv:1"));
    }

    @Test
    void 경계_근거가_없으면_빈_문자열의_값() {
        assertThat(EvidenceDigest.of(List.of())).isEqualTo(sha256(""));
    }

    @Test
    void 정상_소문자_16진_64자_입력은_바꾸지_않는다() {
        List<EvidenceSource> in = new ArrayList<>(List.of(new EvidenceSource("b.csv", 1L), new EvidenceSource("a.csv", 1L)));
        List<EvidenceSource> before = List.copyOf(in);

        assertThat(EvidenceDigest.of(in)).matches("[0-9a-f]{64}");
        assertThat(in).isEqualTo(before);
        assertThat(EvidenceDigest.of(List.of(new EvidenceSource("b.csv", 1L), new EvidenceSource("a.csv", 1L))))
                .isEqualTo(sha256("a.csv:1\nb.csv:1"));   // 바꿀 수 없는 목록도 받는다
    }

    @Test
    void 실패_null_은_NPE() {
        assertThatThrownBy(() -> EvidenceDigest.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EvidenceDigest.of(Arrays.asList(new EvidenceSource(FILE, 1L), null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EvidenceSource(null, 1L)).isInstanceOf(NullPointerException.class);
    }

    private static List<EvidenceSource> sources(long[] rows) {
        List<EvidenceSource> out = new ArrayList<>();
        for (long r : rows) {
            out.add(new EvidenceSource(FILE, r));
        }
        return List.copyOf(out);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
