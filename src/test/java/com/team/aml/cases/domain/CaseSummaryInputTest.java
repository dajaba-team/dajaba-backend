package com.team.aml.cases.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 요약 입력 해시 (CASES 명세 §7.2.1) — 키 순서와 상관없고, 배열 순서·값이 바뀌면 달라진다. */
class CaseSummaryInputTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 객체_키_순서가_달라도_같은_해시() throws Exception {
        CaseSummaryInput a = CaseSummaryInput.of(om.readTree(
                "{\"case_no\":\"C\",\"alert\":{\"b\":1,\"a\":{\"y\":2,\"x\":1}},\"reasons\":[]}"));
        CaseSummaryInput b = CaseSummaryInput.of(om.readTree(
                "{\"reasons\":[],\"alert\":{\"a\":{\"x\":1,\"y\":2},\"b\":1},\"case_no\":\"C\"}"));

        assertThat(a.sha256()).isEqualTo(b.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void 정렬한_JSON은_공백이_없다() throws Exception {
        assertThat(CaseSummaryInput.canonical(om.readTree("{ \"b\" : [2, 1], \"a\" : null }")))
                .isEqualTo("{\"a\":null,\"b\":[2,1]}");
    }

    @Test
    void 배열_순서나_값이_바뀌면_다른_해시() throws Exception {
        String base = CaseSummaryInput.of(om.readTree("{\"evidence_transaction_ids\":[1,2],\"prior_alert_count\":0}")).sha256();

        assertThat(CaseSummaryInput.of(om.readTree("{\"evidence_transaction_ids\":[2,1],\"prior_alert_count\":0}")).sha256())
                .isNotEqualTo(base);
        assertThat(CaseSummaryInput.of(om.readTree("{\"evidence_transaction_ids\":[1,2],\"prior_alert_count\":1}")).sha256())
                .isNotEqualTo(base);
    }

    @Test
    void 스냅샷은_넘긴_노드의_복사본() throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("{\"case_no\":\"C\"}");
        CaseSummaryInput input = CaseSummaryInput.of(body);
        body.put("case_no", "D");

        assertThat(input.snapshot().get("case_no").asText()).isEqualTo("C");
    }

    @Test
    void 객체가_아니면_거부() throws Exception {
        assertThatThrownBy(() -> CaseSummaryInput.of(om.readTree("[]"))).isInstanceOf(IllegalArgumentException.class);
    }
}
