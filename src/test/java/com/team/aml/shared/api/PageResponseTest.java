package com.team.aml.shared.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 목록 응답 모양 고정 (레퍼런스 §12). application.yml 과 같은 SNAKE_CASE 로 직렬화해 본다. */
class PageResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void 정상_Page를_items_page_size_total로_옮긴다() throws Exception {
        Page<String> page = new PageImpl<>(List.of("a", "b", "c"), PageRequest.of(1, 3), 8);

        JsonNode json = mapper.valueToTree(PageResponse.from(page));

        assertThat(json.fieldNames()).toIterable().containsExactly("items", "page", "size", "total");
        assertThat(json.get("items")).hasSize(3);
        assertThat(json.get("page").asInt()).isEqualTo(1);
        assertThat(json.get("size").asInt()).isEqualTo(3);
        assertThat(json.get("total").asLong()).isEqualTo(8);
    }

    @Test
    void 경계_빈_페이지는_빈_배열과_total_0() throws Exception {
        Page<String> empty = Page.empty(PageRequest.of(0, 20));

        JsonNode json = mapper.valueToTree(PageResponse.from(empty));

        assertThat(json.get("items").isArray()).isTrue();
        assertThat(json.get("items")).isEmpty();
        assertThat(json.get("total").asLong()).isZero();
    }

    @Test
    void 실패_items가_null이면_빈_배열로_바꾼다() {
        PageResponse<String> response = new PageResponse<>(null, 0, 20, 0);

        assertThat(response.items()).isNotNull().isEmpty();
    }
}
