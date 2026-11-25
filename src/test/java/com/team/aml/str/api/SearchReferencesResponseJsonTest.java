package com.team.aml.str.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.ai.AiDtos.ReferenceResult;
import com.team.aml.str.application.SearchReferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** S9 응답을 앱과 같은 Jackson 설정(SNAKE_CASE·null 도 씀)으로 — 화면 칸 이름·순서, 관련 구절 excerpt (SCRUM-426). */
@JsonTest
class SearchReferencesResponseJsonTest {

    @Autowired
    ObjectMapper om;

    private static ReferenceResult result(String excerpt) {
        return new ReferenceResult("apg-2025", "APG 2025", "APG", 2025, 53, 0.52, "c1", List.of("CYCLE"), "PUBLIC",
                excerpt);
    }

    private JsonNode item(ReferenceResult r) {
        return om.valueToTree(SearchReferencesResponse.from(new SearchReferences.Result(List.of(r), false)))
                .path("items").get(0);
    }

    @Test
    void 정상_excerpt는_맨_끝_칸이고_다른_칸_이름과_순서는_그대로() {
        JsonNode item = item(result("이 자료의 관련 구절"));

        List<String> keys = new ArrayList<>();
        item.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("doc_title", "publisher", "year", "page", "score", "typologies", "source_type",
                "excerpt");
        assertThat(item.path("excerpt").asText()).isEqualTo("이 자료의 관련 구절");
        assertThat(item.has("doc_id")).isFalse();     // 화면에 안 주는 칸은 그대로 안 줌
        assertThat(item.has("chunk_id")).isFalse();
        assertThat(item.has("text")).isFalse();
    }

    @Test
    void 경계_AI가_excerpt를_안_주면_키는_있고_null() {
        JsonNode item = item(result(null));

        assertThat(item.has("excerpt")).isTrue();
        assertThat(item.get("excerpt").isNull()).isTrue();
    }

    @Test
    void 경계_예전_9칸_생성자는_excerpt_null() {   // 팀원 테스트가 부르는 생성자 (SearchReferencesDbTest)
        ReferenceResult old = new ReferenceResult("ffiec", "FFIEC", null, null, 7, 0.41, "c2", List.of("CYCLE"),
                "PUBLIC");

        assertThat(old.excerpt()).isNull();
        assertThat(item(old).get("excerpt").isNull()).isTrue();
    }
}
