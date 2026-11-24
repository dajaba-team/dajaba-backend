package com.team.aml.str.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.str.application.RequestStrDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱과 같은 Jackson 설정(SNAKE_CASE)으로 S1 비동기 202 본문을 쓴다 — 화면이 읽을 칸 이름 고정 (#183 리뷰). */
@JsonTest
class StrDraftAcceptedResponseJsonTest {

    @Autowired
    ObjectMapper om;

    @Test
    void 정상_202_본문은_case_no_draft_no_request_status() throws Exception {
        JsonNode json = om.valueToTree(StrDraftAcceptedResponse.from(
                new RequestStrDraft.Accepted("CASE-2026-000045", "DRF-000045", "REQUESTED")));

        assertThat(json.path("case_no").asText()).isEqualTo("CASE-2026-000045");
        assertThat(json.path("draft_no").asText()).isEqualTo("DRF-000045");
        assertThat(json.path("request_status").asText()).isEqualTo("REQUESTED");
        assertThat(json.size()).isEqualTo(3);                       // 초안이 아직 없어 case_status·case_version 은 없다
    }
}
