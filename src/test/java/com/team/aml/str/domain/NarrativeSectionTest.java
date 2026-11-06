package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 본문 단락의 cites 칸 (규약 v0.4 8번, AI SCRUM-62). str_revisions jsonb 에 쓰고 읽는 모양을 확인한다.
 * Hibernate 도 Jackson 으로 쓰고 읽어서 기본 ObjectMapper 로 같은 결과를 본다.
 */
class NarrativeSectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // 1
    @Test
    void JSON으로_쓰면_키가_section_text_cites_evidence_ids다() {
        NarrativeSection n = new NarrativeSection(StrSection.DATE, "문장", List.of("period"), List.of(1L, 2L));

        JsonNode written = JSON.valueToTree(n);

        assertThat(written.fieldNames()).toIterable().containsExactlyInAnyOrder("section", "text", "cites", "evidence_ids");
        assertThat(written.get("cites").get(0).asText()).isEqualTo("period");
    }

    // 2
    @Test
    void cites가_있는_JSON을_읽으면_그대로_나온다() throws Exception {
        NarrativeSection n = JSON.readValue("""
                {"section":"DATE","text":"문장","cites":["period","inbound.count"],"evidence_ids":[1,2]}
                """, NarrativeSection.class);

        assertThat(n.cites()).containsExactly("period", "inbound.count");
        assertThat(n.evidenceIds()).containsExactly(1L, 2L);
    }

    // 3: cites 칸 전에 저장된 수정본(키 없음)도 읽혀야 한다
    @Test
    void cites_키가_없는_예전_JSON은_빈_cites로_읽는다() throws Exception {
        NarrativeSection n = JSON.readValue("""
                {"section":"SUMMARY","text":"예전 문장","evidence_ids":[7]}
                """, NarrativeSection.class);

        assertThat(n.cites()).isEmpty();
        assertThat(n.evidenceIds()).containsExactly(7L);
    }

    // 4
    @Test
    void cites_없이_만들면_빈_목록이고_null도_빈_목록이다() {
        assertThat(new NarrativeSection(StrSection.WHO, "문장", List.of(1L)).cites()).isEmpty();
        assertThat(new NarrativeSection(StrSection.WHO, "문장", null, List.of(1L)).cites()).isEmpty();
    }

    // 5
    @Test
    void 넘긴_목록을_밖에서_바꿔도_단락의_cites는_그대로다() {
        List<String> cites = new ArrayList<>(List.of("period"));
        NarrativeSection n = new NarrativeSection(StrSection.DATE, "문장", cites, List.of());

        cites.add("inbound.count");

        assertThat(n.cites()).containsExactly("period");
    }

    // 6
    @Test
    void withText는_문장만_바꾸고_cites와_근거_번호는_그대로다() {
        NarrativeSection n = new NarrativeSection(StrSection.DATE, "원래", List.of("period"), List.of(3L));

        NarrativeSection edited = n.withText("고친 문장");

        assertThat(edited.text()).isEqualTo("고친 문장");
        assertThat(edited.cites()).isEqualTo(n.cites());
        assertThat(edited.evidenceIds()).isEqualTo(n.evidenceIds());
        assertThat(edited.section()).isEqualTo(n.section());
    }
}
