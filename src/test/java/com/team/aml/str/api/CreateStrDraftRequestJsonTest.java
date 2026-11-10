package com.team.aml.str.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱과 같은 Jackson 설정(SNAKE_CASE)으로 S1 본문을 읽는다 — #83 같은 회귀 막기. */
@JsonTest
class CreateStrDraftRequestJsonTest {

    @Autowired
    ObjectMapper om;

    @Test
    void 정상_계약서대로_case_version을_읽는다() throws Exception {
        assertThat(om.readValue("{\"case_version\": 2}", CreateStrDraftRequest.class).caseVersion()).isEqualTo(2L);
    }

    @Test
    void 실패_camelCase_caseVersion은_읽지_않는다() throws Exception {
        assertThat(om.readValue("{\"caseVersion\": 2}", CreateStrDraftRequest.class).caseVersion()).isNull();
    }
}