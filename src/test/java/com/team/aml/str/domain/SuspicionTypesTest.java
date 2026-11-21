package com.team.aml.str.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 서식 VI 이름표 목록 — 프론트 src/shared/str/suspicionTypes.js 와 같은 38항목, 서식 순서. */
class SuspicionTypesTest {

    // 1
    @Test
    void 정상_38항목_코드는_겹치지_않고_분류는_서식_순서대로_다섯() {
        assertThat(SuspicionTypes.ALL).hasSize(38);
        assertThat(SuspicionTypes.ALL).extracting(SuspicionTypes.Entry::code).doesNotHaveDuplicates();
        assertThat(SuspicionTypes.ALL.stream().map(SuspicionTypes.Entry::group).distinct())   // 분류가 섞이지 않고 VI-1 → VI-5
                .containsExactly(SuspicionTypes.Group.values());
        assertThat(SuspicionTypes.Group.VI_2.title()).isEqualTo("VI-2. 의심스러운 거래자의 태도 및 특징 관련");
        assertThat(SuspicionTypes.ALL.get(0).code()).isEqualTo("DEMO_VI1_01");
        assertThat(SuspicionTypes.ALL.get(37).code()).isEqualTo("DEMO_VI5_01");
    }

    // 2
    @Test
    void 정상_아는_코드는_고른_순서가_아니라_서식_순서로_한_번씩() {
        assertThat(SuspicionTypes.known(List.of("DEMO_VI4_12", "DEMO_VI2_09", "DEMO_VI4_12", "모름")))
                .extracting(SuspicionTypes.Entry::label)
                .containsExactly("불특정다수와의 거래", "빈번한 입출금(입출고)");
    }

    // 3
    @Test
    void 정상_모르는_코드는_들어온_순서로_한_번씩() {
        assertThat(SuspicionTypes.unknown(List.of("B", "DEMO_VI2_09", "A", "B"))).containsExactly("B", "A");
        assertThat(SuspicionTypes.unknownLabel("VI-9")).isEqualTo("확인 필요한 유형 (VI-9)");
    }

    // 4
    @Test
    void 정상_코드로_찾기는_한_번에_없거나_null이면_빈_값() {
        assertThat(SuspicionTypes.find("DEMO_VI2_09")).get()
                .extracting(SuspicionTypes.Entry::label).isEqualTo("불특정다수와의 거래");
        assertThat(SuspicionTypes.find("VI-2-9")).isEmpty();
        assertThat(SuspicionTypes.find(null)).isEmpty();
    }
}
